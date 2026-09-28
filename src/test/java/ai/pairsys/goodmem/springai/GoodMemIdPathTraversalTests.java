package ai.pairsys.goodmem.springai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.ai.rag.Query;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static ai.pairsys.goodmem.springai.Fixtures.boundary;
import static ai.pairsys.goodmem.springai.Fixtures.memoriesPage;
import static ai.pairsys.goodmem.springai.Fixtures.memoryJson;
import static ai.pairsys.goodmem.springai.Fixtures.ndjson;
import static ai.pairsys.goodmem.springai.Fixtures.spaceJson;
import static ai.pairsys.goodmem.springai.Fixtures.spacesPage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every id that can reach a GoodMem URL path is refused unless it is a UUID, and nothing
 * is sent.
 *
 * <p>
 * The SDK builds {@code "/v1/memories/" + id} and OkHttp's
 * {@code addEncodedPathSegments} resolves {@code ..} and {@code %2e%2e}, so in 0.2.0
 * {@code goodmem_delete_memory("../spaces/<uuid>")} sent {@code DELETE /v1/spaces/<uuid>}
 * and reported success. These tests drive the real SDK and OkHttp against a plain JDK
 * HTTP server that records every request line exactly as it arrived and answers every
 * request with success, the way a server that honoured the traversal would.
 */
class GoodMemIdPathTraversalTests {

	/** The traversal target, and a valid-looking UUID. */
	private static final String U = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

	/** A valid id used where the entry point under test takes a second one. */
	private static final String OTHER = "01a0d16b-bbcd-701c-bfb4-fa306021e078";

	private static final String CREATED_MEMORY_ID = "01a0d16b-bbcd-701c-bfb4-fa306021e099";

	private static final List<String> TRAVERSALS = List.of("../spaces/" + U, "a/../../spaces/" + U,
			"%2e%2e/spaces/" + U, "..%2Fspaces%2F" + U, U + "/../../spaces/" + U, "", " " + U, U + "?x=1",
			U + "#frag", "../embedders/" + U, U + "\n", U + " ", "../../v1/spaces/" + U, "%2E%2E/spaces/" + U);

	private RecordingServer server;

	private GoodMemConnection connection;

	@TempDir
	Path uploadDir;

	@BeforeEach
	void start() throws IOException {
		this.server = new RecordingServer();
		this.connection = GoodMemConnection.builder().baseUrl(this.server.baseUrl()).apiKey("test-key").build();
		Files.writeString(this.uploadDir.resolve("notes.txt"), "inside");
	}

	@AfterEach
	void stop() {
		this.connection.close();
		this.server.close();
	}

	// ----- the entry points -----

	@FunctionalInterface
	interface Call {

		Object invoke(GoodMemConnection connection, String id, Path uploadDir);

	}

	/**
	 * One place an outside id enters the package. {@code tool} entry points refuse with
	 * the package's {@code success=false} map; the retriever, configured by the developer,
	 * refuses with {@link IllegalArgumentException} like its other builder checks.
	 * {@code method} and {@code path} are the id-carrying request a valid id must produce;
	 * {@code body} is a fragment it must carry, for ids that travel in the body.
	 */
	record EntryPoint(String name, String field, boolean tool, Call call, String method, String path,
			String body, Set<String> alsoAllowed) {

		@Override
		public String toString() {
			return this.name;
		}

	}

	static List<EntryPoint> entryPoints() {
		return List.of(
				new EntryPoint("goodmem_get_space", "spaceId", true,
						(c, id, dir) -> new GoodMemAdminTools(c).getSpace(id), "GET", "/v1/spaces/%s", null, Set.of()),
				new EntryPoint("goodmem_delete_space", "spaceId", true,
						(c, id, dir) -> new GoodMemAdminTools(c).deleteSpace(id), "DELETE", "/v1/spaces/%s", null,
						Set.of()),
				new EntryPoint("goodmem_list_memories", "spaceId", true,
						(c, id, dir) -> new GoodMemAdminTools(c).listMemories(id), "GET", "/v1/spaces/%s/memories",
						null, Set.of()),
				new EntryPoint("goodmem_get_memory", "memoryId", true,
						(c, id, dir) -> new GoodMemAdminTools(c).getMemory(id), "GET", "/v1/memories/%s", null,
						Set.of()),
				new EntryPoint("goodmem_delete_memory", "memoryId", true,
						(c, id, dir) -> new GoodMemAdminTools(c).deleteMemory(id), "DELETE", "/v1/memories/%s", null,
						Set.of()),
				new EntryPoint("goodmem_create_space", "embedderId", true,
						(c, id, dir) -> new GoodMemAdminTools(c).createSpace("notes", id, null, null), "POST",
						"/v1/spaces", "\"embedderId\":\"%s\"", Set.of("GET /v1/spaces")),
				new EntryPoint("goodmem_create_memory", "spaceId", true,
						(c, id, dir) -> new GoodMemAdminTools(c, false, Duration.ZERO, 10).createMemory(id, "hello",
								null),
						"POST", "/v1/memories", "\"spaceId\":\"%s\"", Set.of()),
				new EntryPoint("goodmem_upload_file", "spaceId", true,
						(c, id, dir) -> new GoodMemUploadTool(c, dir).upload(id, "notes.txt", null), "POST",
						"/v1/memories", "\"spaceId\":\"%s\"", Set.of()),
				new EntryPoint("GoodMemDocumentRetriever.spaceId", "spaceId", false,
						(c, id, dir) -> GoodMemDocumentRetriever.builder()
							.connection(c)
							.spaceId(id)
							.build()
							.retrieve(new Query("q")),
						"POST", "/v1/memories:retrieve", "\"spaceId\":\"%s\"", Set.of()),
				new EntryPoint("GoodMemDocumentRetriever.rerankerId", "rerankerId", false,
						(c, id, dir) -> GoodMemDocumentRetriever.builder()
							.connection(c)
							.spaceId(OTHER)
							.rerankerId(id)
							.build()
							.retrieve(new Query("q")),
						"POST", "/v1/memories:retrieve", "\"reranker_id\":\"%s\"", Set.of()));
	}

	static Stream<Arguments> everyEntryPointWithEveryTraversal() {
		List<Arguments> cases = new ArrayList<>();
		for (EntryPoint entryPoint : entryPoints()) {
			for (String payload : TRAVERSALS) {
				cases.add(Arguments.of(entryPoint, payload));
			}
		}
		return cases.stream();
	}

	static Stream<Arguments> everyEntryPointWithAValidId() {
		List<Arguments> cases = new ArrayList<>();
		for (EntryPoint entryPoint : entryPoints()) {
			cases.add(Arguments.of(entryPoint, U));
			cases.add(Arguments.of(entryPoint, U.toUpperCase(Locale.ROOT)));
		}
		return cases.stream();
	}

	// ----- refusals -----

	@ParameterizedTest(name = "{0} refuses {1}")
	@MethodSource("everyEntryPointWithEveryTraversal")
	void aNonUuidIdIsRefusedAndNothingIsSent(EntryPoint entryPoint, String payload) {
		Object outcome = invoke(entryPoint, payload);

		assertThat(this.server.requests())
			.as("%s(%s) must send nothing; the server received %s; outcome %s", entryPoint, printable(payload),
					this.server.requests(), describe(outcome))
			.isEmpty();
		if (entryPoint.tool()) {
			assertThat(outcome).as("outcome of %s(%s)", entryPoint, printable(payload)).isInstanceOf(Map.class);
			Map<?, ?> result = (Map<?, ?>) outcome;
			assertThat(result.get("success")).as("success").isEqualTo(false);
			assertThat(String.valueOf(result.get("error"))).contains(entryPoint.field()).contains("UUID");
		}
		else {
			assertThat(outcome).as("outcome of %s(%s)", entryPoint, printable(payload))
				.isInstanceOf(IllegalArgumentException.class);
			assertThat(((Throwable) outcome).getMessage()).contains(entryPoint.field()).contains("UUID");
		}
	}

	// ----- a valid id still reaches exactly the intended request -----

	@ParameterizedTest(name = "{0} sends {1} where it belongs")
	@MethodSource("everyEntryPointWithAValidId")
	void aUuidReachesExactlyTheIntendedPath(EntryPoint entryPoint, String id) {
		Object outcome = invoke(entryPoint, id);
		String canonical = id.toLowerCase(Locale.ROOT);

		assertThat(outcome).as("outcome").isNotInstanceOf(Throwable.class);
		if (entryPoint.tool()) {
			assertThat(((Map<?, ?>) outcome).get("success")).as("success; %s", outcome).isEqualTo(true);
		}
		String expected = entryPoint.method() + " " + entryPoint.path().formatted(canonical);
		List<Request> requests = this.server.requests();
		assertThat(requests).extracting(Request::line)
			.as("requests")
			.allMatch(line -> line.equals(expected) || entryPoint.alsoAllowed().contains(line))
			.containsOnlyOnce(expected);
		if (entryPoint.body() != null) {
			Request carrying = requests.stream().filter(r -> r.line().equals(expected)).findFirst().orElseThrow();
			assertThat(carrying.body()).contains(entryPoint.body().formatted(canonical));
		}
	}

	// ----- an id the server returned goes through the same check -----

	@Test
	void aServerIssuedMemoryIdThatIsNotAUuidIsNotPolled() {
		this.server.createdMemoryId = "../spaces/" + U;

		Map<String, Object> result = new GoodMemAdminTools(this.connection, true, Duration.ofSeconds(1), 10)
			.createMemory(OTHER, "hello", null);

		assertThat(this.server.requests()).extracting(Request::line)
			.as("only the write; no poll of a traversed path")
			.containsExactly("POST /v1/memories");
		assertThat(result).containsEntry("success", true);
		assertThat((String) result.get("status")).contains("not polled");
	}

	// ----- through Spring AI, the way a model's tool call arrives -----

	@Test
	void aModelsToolCallWithATraversalIsRefusedAndNothingIsSent() {
		ToolCallback delete = Arrays.stream(ToolCallbacks.from(new GoodMemAdminTools(this.connection)))
			.filter(callback -> callback.getToolDefinition().name().equals("goodmem_delete_memory"))
			.findFirst()
			.orElseThrow();

		String reply = delete.call("{\"memoryId\":\"../spaces/" + U + "\"}");

		assertThat(this.server.requests()).as("the server received %s; reply %s", this.server.requests(), reply)
			.isEmpty();
		assertThat(reply).contains("\"success\":false").contains("memoryId must be a UUID");
	}

	// ----- the model is told -----

	@Test
	void everyIdArgumentTheModelSeesIsDescribedAsAUuid() throws Exception {
		ObjectMapper json = new ObjectMapper();
		List<String> idArguments = new ArrayList<>();
		for (ToolCallback callback : ToolCallbacks.from(new GoodMemAdminTools(this.connection),
				new GoodMemUploadTool(this.connection, this.uploadDir), new GoodMemSearchTool(GoodMemDocumentRetriever
					.builder()
					.connection(this.connection)
					.spaceId(OTHER)
					.build()))) {
			JsonNode schema = json.readTree(callback.getToolDefinition().inputSchema());
			Iterator<Map.Entry<String, JsonNode>> properties = schema.path("properties").fields();
			while (properties.hasNext()) {
				Map.Entry<String, JsonNode> property = properties.next();
				if (property.getKey().endsWith("Id")) {
					String where = callback.getToolDefinition().name() + "." + property.getKey();
					idArguments.add(where);
					assertThat(property.getValue().path("description").asText()).as(where).contains("UUID");
				}
			}
		}
		assertThat(idArguments).hasSize(8);
	}

	// ----- helpers -----

	private Object invoke(EntryPoint entryPoint, String id) {
		try {
			return entryPoint.call().invoke(this.connection, id, this.uploadDir);
		}
		catch (RuntimeException ex) {
			return ex;
		}
	}

	private static String printable(String payload) {
		return "\"" + payload.replace("\n", "\\n") + "\"";
	}

	private static String describe(Object outcome) {
		return (outcome instanceof Throwable t) ? t.getClass().getSimpleName() + ": " + t.getMessage()
				: String.valueOf(outcome);
	}

	/** One request as it arrived: method, raw path and query, body. */
	record Request(String method, String rawPath, String rawQuery, String body) {

		String line() {
			return this.method + " " + this.rawPath;
		}

		@Override
		public String toString() {
			return line() + ((this.rawQuery != null) ? "?" + this.rawQuery : "");
		}

	}

	/**
	 * A JDK HTTP server that records every request before answering it with success. It
	 * does no path normalisation of its own, so the path recorded is the one the client
	 * sent.
	 */
	static final class RecordingServer implements AutoCloseable {

		private final HttpServer server;

		private final List<Request> requests = new CopyOnWriteArrayList<>();

		volatile String createdMemoryId = CREATED_MEMORY_ID;

		RecordingServer() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			this.server.createContext("/", this::handle);
			this.server.start();
		}

		String baseUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		List<Request> requests() {
			return List.copyOf(this.requests);
		}

		private void handle(HttpExchange exchange) throws IOException {
			byte[] received = exchange.getRequestBody().readAllBytes();
			URI uri = exchange.getRequestURI();
			String method = exchange.getRequestMethod();
			String path = uri.getRawPath();
			this.requests.add(new Request(method, path, uri.getRawQuery(), new String(received, StandardCharsets.UTF_8)));
			if ("DELETE".equals(method)) {
				exchange.sendResponseHeaders(204, -1);
				exchange.close();
				return;
			}
			String contentType = "application/json";
			String body;
			if (path.endsWith(":retrieve")) {
				contentType = "application/x-ndjson";
				body = ndjson(boundary("BEGIN"), boundary("END"));
			}
			else if (path.equals("/v1/spaces")) {
				body = "POST".equals(method) ? spaceJson("notes", OTHER, U) : spacesPage(null);
			}
			else if (path.equals("/v1/memories")) {
				body = memoryJson(this.createdMemoryId, "PENDING", null);
			}
			else if (path.matches("/v1/spaces/[^/]+/memories")) {
				body = memoriesPage(null, memoryJson(CREATED_MEMORY_ID, "COMPLETED", null));
			}
			else if (path.startsWith("/v1/spaces/")) {
				body = spaceJson("notes", OTHER, U);
			}
			else if (path.startsWith("/v1/memories/")) {
				body = memoryJson(CREATED_MEMORY_ID, "COMPLETED", "hello");
			}
			else {
				body = "{}";
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}

		@Override
		public void close() {
			this.server.stop(0);
		}

	}

}
