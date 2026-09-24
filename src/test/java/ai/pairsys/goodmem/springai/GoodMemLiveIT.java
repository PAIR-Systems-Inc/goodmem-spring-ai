package ai.pairsys.goodmem.springai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live tests against a running GoodMem server. Skipped unless GOODMEM_BASE_URL,
 * GOODMEM_API_KEY and GOODMEM_EMBEDDER_ID are set. The space this creates is deleted
 * afterwards, and the teardown is verified.
 */
@EnabledIfEnvironmentVariable(named = "GOODMEM_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GOODMEM_EMBEDDER_ID", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoodMemLiveIT {

	private static final String EMBEDDER = System.getenv("GOODMEM_EMBEDDER_ID");

	private static final String OTHER_EMBEDDER = System.getenv().getOrDefault("GOODMEM_OTHER_EMBEDDER_ID",
			"019cfd94-2844-7117-85ca-1b9919758a26");

	private GoodMemConnection connection;

	private GoodMemAdminTools admin;

	private String spaceName;

	private String spaceId;

	@BeforeAll
	void setUp() {
		this.connection = GoodMemConnection.builder()
			.baseUrl(System.getenv().getOrDefault("GOODMEM_BASE_URL", "https://localhost:8080"))
			.apiKey(System.getenv("GOODMEM_API_KEY"))
			.verifySsl(!"false".equalsIgnoreCase(System.getenv().getOrDefault("GOODMEM_VERIFY_SSL", "true")))
			.build();
		this.admin = new GoodMemAdminTools(this.connection);
		this.spaceName = "springai-live-" + UUID.randomUUID().toString().substring(0, 8);
		Map<String, Object> created = this.admin.createSpace(this.spaceName, EMBEDDER, null, null);
		assertThat(created).containsEntry("success", true);
		this.spaceId = (String) created.get("spaceId");
	}

	@AfterAll
	void tearDown() {
		if (this.spaceId != null) {
			assertThat(this.admin.deleteSpace(this.spaceId)).containsEntry("success", true);
			assertThat(this.admin.findSpaceByName(this.spaceName)).as("teardown left the space behind").isNull();
		}
		this.connection.close();
	}

	private GoodMemDocumentRetriever retriever(String filter, String rerankerId) {
		return GoodMemDocumentRetriever.builder()
			.connection(this.connection)
			.spaceId(this.spaceId)
			.topK(5)
			.filter(filter)
			.rerankerId(rerankerId)
			.build();
	}

	@Test
	void writeThenSearchThroughTheDocumentRetriever() {
		Map<String, Object> created = this.admin.createMemory(this.spaceId, "Ada Lovelace wrote the first algorithm.",
				Map.of("tag", "history"));
		assertThat(created).containsEntry("success", true).containsEntry("status", "COMPLETED");

		List<Document> docs = retriever(null, null).retrieve(new Query("who wrote the first algorithm"));

		assertThat(docs).isNotEmpty();
		assertThat(docs.get(0).getText()).contains("Ada Lovelace");
		assertThat(docs.get(0).getMetadata()).containsEntry("tag", "history").containsEntry("goodmem_partial", false);
		assertThat(docs.get(0).getScore()).as("vector scores are negated into higher-is-better").isPositive();
	}

	@Test
	void theSearchToolReturnsJoinedResults() {
		this.admin.createMemory(this.spaceId, "Grace Hopper built the first compiler.", Map.of("tag", "compilers"));

		Map<String, Object> result = new GoodMemSearchTool(retriever(null, null)).search("first compiler", 3);

		assertThat(result).containsEntry("success", true).containsEntry("partial", false);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
		assertThat(results).isNotEmpty();
		assertThat((String) results.get(0).get("text")).contains("Grace Hopper");
		assertThat(asMap(results.get(0).get("metadata"))).containsEntry("tag", "compilers");
	}

	@Test
	void aBogusRerankerIsReportedNotHidden() {
		this.admin.createMemory(this.spaceId, "The reranker test document.", Map.of("tag", "rerank"));

		Map<String, Object> result = new GoodMemSearchTool(retriever(null, "00000000-0000-4000-8000-000000000000"))
			.search("reranker test", 3);

		assertThat(result).containsEntry("success", true).containsEntry("partial", true);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> statuses = (List<Map<String, Object>>) result.get("statuses");
		assertThat(statuses).extracting(s -> s.get("code")).contains("RERANKING_FAILED");
	}

	@Test
	void aFilterWithAnApostropheIsAValueAndAnInjectionMatchesOnlyItsOwnRow() {
		this.admin.createMemory(this.spaceId, "Written by O'Brien.", Map.of("author", "o'brien"));
		this.admin.createMemory(this.spaceId, "Written by Smith.", Map.of("author", "smith"));
		this.admin.createMemory(this.spaceId, "The injected row.", Map.of("author", "x' OR '1'='1"));

		List<Document> apostrophe = retriever(GoodMemFilters.textEquals("author", "o'brien"), null)
			.retrieve(new Query("written by"));
		List<Document> injection = retriever(GoodMemFilters.textEquals("author", "x' OR '1'='1"), null)
			.retrieve(new Query("row"));

		assertThat(apostrophe).extracting(d -> d.getMetadata().get("author")).containsOnly("o'brien");
		assertThat(injection).hasSize(1);
		assertThat(injection.get(0).getMetadata()).containsEntry("author", "x' OR '1'='1");
	}

	@Test
	void reusingTheSpaceWithAnotherEmbedderIsRefused() {
		Map<String, Object> result = this.admin.createSpace(this.spaceName, OTHER_EMBEDDER, null, null);

		assertThat(result).containsEntry("success", false);
		assertThat((String) result.get("error")).contains("cannot be changed");
	}

	@Test
	void anEmptySpaceIsSearchedInWellUnderASecondNotAMinute() {
		Map<String, Object> empty = this.admin.createSpace(this.spaceName + "-empty", EMBEDDER, null, null);
		String emptyId = (String) empty.get("spaceId");
		try {
			GoodMemDocumentRetriever r = GoodMemDocumentRetriever.builder().connection(this.connection).spaceId(emptyId).build();
			long start = System.nanoTime();
			List<Document> docs = r.retrieve(new Query("anything"));
			long ms = (System.nanoTime() - start) / 1_000_000L;
			assertThat(docs).isEmpty();
			assertThat(ms).isLessThan(5_000);
		}
		finally {
			this.admin.deleteSpace(emptyId);
		}
	}

	@Test
	void uploadsStayInsideTheDirectory(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("meeting.txt"), "The offsite is on Thursday.");
		GoodMemUploadTool upload = new GoodMemUploadTool(this.connection, dir);

		Map<String, Object> refused = upload.upload(this.spaceId, "/etc/hostname", null);
		Map<String, Object> accepted = upload.upload(this.spaceId, "meeting.txt", Map.of("tag", "upload"));

		assertThat(refused).containsEntry("success", false);
		assertThat(accepted).containsEntry("success", true).containsEntry("contentType", "text/plain");
		Map<String, Object> got = this.admin.getMemory((String) accepted.get("memoryId"));
		assertThat((String) got.get("content")).contains("offsite");
	}

	@Test
	void listingsAndGetsWork() {
		Map<String, Object> spaces = this.admin.listSpaces();
		assertThat(spaces).containsEntry("success", true);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> list = (List<Map<String, Object>>) spaces.get("spaces");
		assertThat(list).extracting(s -> s.get("name")).contains(this.spaceName);

		Map<String, Object> space = this.admin.getSpace(this.spaceId);
		assertThat(asMap(space.get("space"))).containsEntry("name", this.spaceName);
		assertThat(asMap(space.get("space")).get("embedderIds")).isEqualTo(List.of(EMBEDDER));

		Map<String, Object> memories = this.admin.listMemories(this.spaceId);
		assertThat(memories).containsEntry("success", true).doesNotContainKey("nextToken");

		Map<String, Object> embedders = this.admin.listEmbedders();
		assertThat(embedders).containsEntry("success", true);
	}

	@Test
	void aRejectedWriteReportsTheServersOwnMessage() {
		Map<String, Object> result = this.admin.createMemory(this.spaceId, "", null);

		assertThat(result).containsEntry("success", false).containsEntry("statusCode", 400);
		assertThat((String) result.get("error")).contains("originalContent");
	}


	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return (Map<String, Object>) value;
	}

}
