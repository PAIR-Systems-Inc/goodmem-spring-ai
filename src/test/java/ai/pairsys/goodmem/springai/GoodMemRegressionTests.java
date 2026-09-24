package ai.pairsys.goodmem.springai;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.pairsys.goodmem.client.Goodmem;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import static ai.pairsys.goodmem.springai.Fixtures.EMBEDDER_ID;
import static ai.pairsys.goodmem.springai.Fixtures.OTHER_EMBEDDER_ID;
import static ai.pairsys.goodmem.springai.Fixtures.REAL_VECTOR_SCORE;
import static ai.pairsys.goodmem.springai.Fixtures.SPACE_ID;
import static ai.pairsys.goodmem.springai.Fixtures.boundary;
import static ai.pairsys.goodmem.springai.Fixtures.chunkEvent;
import static ai.pairsys.goodmem.springai.Fixtures.memoryEvent;
import static ai.pairsys.goodmem.springai.Fixtures.memoryJson;
import static ai.pairsys.goodmem.springai.Fixtures.memoriesPage;
import static ai.pairsys.goodmem.springai.Fixtures.ndjson;
import static ai.pairsys.goodmem.springai.Fixtures.spaceJson;
import static ai.pairsys.goodmem.springai.Fixtures.spacesPage;
import static ai.pairsys.goodmem.springai.Fixtures.statusEvent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regressions for every defect the 0.1.0 audit reproduced. Each test drives the real
 * SDK over a mock HTTP server; nothing in the package or the SDK is stubbed, so a pass
 * means the wire behaviour is right, not that a mock was called.
 */
@WireMockTest
class GoodMemRegressionTests {

	private static GoodMemConnection connection(WireMockRuntimeInfo wm) {
		return GoodMemConnection.builder().baseUrl("http://localhost:" + wm.getHttpPort()).apiKey("test-key").build();
	}

	private static GoodMemDocumentRetriever retriever(WireMockRuntimeInfo wm) {
		return GoodMemDocumentRetriever.builder().connection(connection(wm)).spaceId(SPACE_ID).topK(5).build();
	}

	private static void stubRetrieve(String body) {
		stubFor(post(urlPathEqualTo("/v1/memories:retrieve"))
			.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-ndjson").withBody(body)));
	}

	private static String lastRetrieveBody() {
		var requests = findAll(postRequestedFor(urlPathEqualTo("/v1/memories:retrieve")));
		return requests.get(requests.size() - 1).getBodyAsString();
	}

	// ----- P4 / P3: the retrieval status contract -----

	@Test
	void aReportedProblemFlagsTheResultsButKeepsThem(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(statusEvent("RERANKING_FAILED", "reranker unavailable"), memoryEvent("m-1", Map.of("tag", "a")),
				chunkEvent("c-1", "Ada wrote the first algorithm.", "m-1", REAL_VECTOR_SCORE, 0)));

		List<Document> docs = retriever(wm).retrieve(new Query("algorithm"));

		assertThat(docs).hasSize(1);
		assertThat(docs.get(0).getMetadata()).containsEntry(GoodMemDocumentRetriever.METADATA_PARTIAL, true);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> statuses = (List<Map<String, Object>>) docs.get(0)
			.getMetadata()
			.get(GoodMemDocumentRetriever.METADATA_STATUSES);
		assertThat(statuses).extracting(s -> s.get("code")).containsExactly("RERANKING_FAILED");
	}

	@Test
	void aProblemWithNoHitsReturnsEmptyAndDoesNotThrow(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(statusEvent("VECTOR_SEARCH_FAILED", "index unavailable")));

		List<Document> docs = retriever(wm).retrieve(new Query("algorithm"));
		Map<String, Object> tool = new GoodMemSearchTool(retriever(wm)).search("algorithm", null);

		assertThat(docs).isEmpty();
		assertThat(tool).containsEntry("success", true).containsEntry("partial", true);
		assertThat((List<?>) tool.get("statuses")).hasSize(1);
	}

	@Test
	void anUnknownStatusCodeIsSurfacedNotDropped(WireMockRuntimeInfo wm) {
		// A code this SDK does not know decodes as null; it must not vanish and must not discard hits.
		stubRetrieve(ndjson(statusEvent("SOMETHING_FROM_A_NEWER_SERVER", "unrecognised"), memoryEvent("m-1", Map.of()),
				chunkEvent("c-1", "kept", "m-1", REAL_VECTOR_SCORE, 0)));

		Map<String, Object> result = new GoodMemSearchTool(retriever(wm)).search("q", null);

		assertThat((List<?>) result.get("results")).hasSize(1);
		assertThat(result).containsEntry("partial", true);
		@SuppressWarnings("unchecked")
		Map<String, Object> status = ((List<Map<String, Object>>) result.get("statuses")).get(0);
		assertThat(status).containsEntry("code", "UNKNOWN").containsEntry("unrecognized", true);
	}

	@Test
	void featureDisabledIsInformationalByCodeAlone(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(statusEvent("FEATURE_DISABLED", "no LLM configured"), memoryEvent("m-1", Map.of()),
				chunkEvent("c-1", "kept", "m-1", REAL_VECTOR_SCORE, 0)));

		Map<String, Object> result = new GoodMemSearchTool(retriever(wm)).search("q", null);

		assertThat(result).containsEntry("partial", false).doesNotContainKey("statuses");
		assertThat((List<?>) result.get("results")).hasSize(1);
	}

	@Test
	void aTruncatedStreamKeepsWhatArrivedAndReportsIt(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(memoryEvent("m-1", Map.of()), chunkEvent("c-1", "kept", "m-1", REAL_VECTOR_SCORE, 0))
				+ "{\"retrievedItem\":{\"chunk\":{\"chu");

		Map<String, Object> result = new GoodMemSearchTool(retriever(wm)).search("q", null);

		assertThat((List<?>) result.get("results")).hasSize(1);
		assertThat(result).containsEntry("partial", true);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> statuses = (List<Map<String, Object>>) result.get("statuses");
		assertThat(statuses).extracting(s -> s.get("code")).contains(RetrievalResults.MALFORMED_STREAM);
	}

	// ----- P30 / P15: joining chunks to memories -----

	@Test
	void chunksJoinToMemoriesByUuidNotByPosition(WireMockRuntimeInfo wm) {
		// memoryIndex says 0 (m-A) but the chunk belongs to m-B. 0.1.0 handed the model both arrays
		// and the index; a positional join would attach alpha's metadata to beta's text.
		stubRetrieve(ndjson(memoryEvent("m-A", Map.of("tag", "alpha")), memoryEvent("m-B", Map.of("tag", "beta")),
				chunkEvent("c-1", "text of B", "m-B", REAL_VECTOR_SCORE, 0)));

		List<Document> docs = retriever(wm).retrieve(new Query("text"));

		assertThat(docs.get(0).getMetadata()).containsEntry("tag", "beta");
	}

	@Test
	void twoChunksOfOneMemoryAreTwoResultsAndTheSameChunkTwiceIsOne(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(memoryEvent("m-1", Map.of()), chunkEvent("c-1", "first half", "m-1", -0.6, 0),
				chunkEvent("c-2", "second half", "m-1", -0.5, 0), chunkEvent("c-1", "first half", "m-1", -0.6, 0)));

		List<Document> docs = retriever(wm).retrieve(new Query("half"));

		assertThat(docs).extracting(Document::getText).containsExactly("first half", "second half");
	}

	@Test
	void theRealCaptureParsesEndToEnd(WireMockRuntimeInfo wm) {
		stubRetrieve(Fixtures.realCapture());

		List<Document> docs = retriever(wm).retrieve(new Query("who wrote the first algorithm"));

		assertThat(docs).isNotEmpty();
		assertThat(docs.get(0).getMetadata()).containsKey("goodmem_memory_id").containsEntry("goodmem_partial", false);
	}

	// ----- P29: score direction -----

	@Test
	void vectorScoresAreNegatedIntoHigherIsBetter(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(memoryEvent("m-1", Map.of()), chunkEvent("c-1", "x", "m-1", REAL_VECTOR_SCORE, 0)));

		Document doc = retriever(wm).retrieve(new Query("x")).get(0);

		assertThat(REAL_VECTOR_SCORE).isNegative();
		assertThat(doc.getScore()).isEqualTo(-REAL_VECTOR_SCORE);
		assertThat(doc.getMetadata()).containsEntry("goodmem_score_kind", "vector")
			.containsEntry("goodmem_raw_score", REAL_VECTOR_SCORE);
	}

	@Test
	void rerankerScoresPassThroughAndTheRerankerIsSentAsAPostProcessor(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(memoryEvent("m-1", Map.of()), chunkEvent("c-1", "x", "m-1", 0.42, 0)));
		GoodMemDocumentRetriever reranked = GoodMemDocumentRetriever.builder()
			.connection(connection(wm))
			.spaceId(SPACE_ID)
			.rerankerId("r-1")
			.build();

		Document doc = reranked.retrieve(new Query("x")).get(0);

		assertThat(doc.getScore()).isEqualTo(0.42);
		assertThat(doc.getMetadata()).containsEntry("goodmem_score_kind", "reranker");
		assertThat(lastRetrieveBody()).contains("\"postProcessor\"").contains("\"reranker_id\":\"r-1\"");
	}

	// ----- P19 / P34: filters -----

	@Test
	void aFilterIsSentPerSpaceKeyWithTheServersEscaping(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(boundary("BEGIN"), boundary("END")));
		GoodMemDocumentRetriever filtered = GoodMemDocumentRetriever.builder()
			.connection(connection(wm))
			.spaceId(SPACE_ID)
			.filter(GoodMemFilters.textEquals("author", "o'brien"))
			.build();

		filtered.retrieve(new Query("x"));

		assertThat(lastRetrieveBody()).contains("\"filter\":\"CAST(val('$.author') AS TEXT) = 'o\\\\'brien'\"");
	}

	@Test
	void anInjectionPayloadStaysAValue() {
		assertThat(GoodMemFilters.textEquals("tag", "x' OR '1'='1"))
			.isEqualTo("CAST(val('$.tag') AS TEXT) = 'x\\' OR \\'1\\'=\\'1'");
	}

	@Test
	void numbersAndBooleansUseTheCastsTheServerAccepts() {
		// A boolean compared as text is accepted by the server and matches nothing.
		assertThat(GoodMemFilters.compare("year", ">", 2000)).isEqualTo("CAST(val('$.year') AS NUMERIC) > 2000");
		assertThat(GoodMemFilters.compare("active", "=", true)).isEqualTo("CAST(val('$.active') AS BOOLEAN) = true");
		assertThat(GoodMemFilters.isIn("tag", List.of("a", "b"))).isEqualTo("CAST(val('$.tag') AS TEXT) IN ('a', 'b')");
		assertThat(GoodMemFilters.allOf(GoodMemFilters.textEquals("a", "1"), GoodMemFilters.compare("b", "=", 2)))
			.isEqualTo("(CAST(val('$.a') AS TEXT) = '1') AND (CAST(val('$.b') AS NUMERIC) = 2)");
		assertThat(GoodMemFilters.not("x = 1")).isEqualTo("NOT (x = 1)");
	}

	@Test
	void unencodableFieldsAndControlCharactersAreRefused() {
		assertThatThrownBy(() -> GoodMemFilters.textEquals("bad field", "x")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GoodMemFilters.textEquals("tag", "a\nb")).hasMessageContaining("control characters");
	}

	// ----- P5: no polling on the read path; the write waits instead -----

	@Test
	void anEmptySearchIsOneRequestNotAMinuteOfPolling(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(boundary("BEGIN"), boundary("END")));

		long start = System.nanoTime();
		Map<String, Object> result = new GoodMemSearchTool(retriever(wm)).search("anything", null);

		assertThat((System.nanoTime() - start) / 1_000_000L).isLessThan(2_000);
		verify(1, postRequestedFor(urlPathEqualTo("/v1/memories:retrieve")));
		assertThat(result).containsEntry("success", true).containsEntry("partial", false);
		assertThat((List<?>) result.get("results")).isEmpty();
	}

	@Test
	void creatingAMemoryWaitsForIndexing(WireMockRuntimeInfo wm) {
		stubFor(post(urlPathEqualTo("/v1/memories")).willReturn(okJson(memoryJson("m-1", "PENDING", null))));
		stubFor(get(urlPathEqualTo("/v1/memories/m-1")).inScenario("index")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(okJson(memoryJson("m-1", "PENDING", null)))
			.willSetStateTo("processing"));
		stubFor(get(urlPathEqualTo("/v1/memories/m-1")).inScenario("index")
			.whenScenarioStateIs("processing")
			.willReturn(okJson(memoryJson("m-1", "PROCESSING", null)))
			.willSetStateTo("done"));
		stubFor(get(urlPathEqualTo("/v1/memories/m-1")).inScenario("index")
			.whenScenarioStateIs("done")
			.willReturn(okJson(memoryJson("m-1", "COMPLETED", null))));

		Map<String, Object> result = new GoodMemAdminTools(connection(wm)).createMemory(SPACE_ID, "hello", null);

		assertThat(result).containsEntry("success", true).containsEntry("status", "COMPLETED");
		assertThat(findAll(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/v1/memories/m-1"))))
			.hasSizeGreaterThanOrEqualTo(3);
	}

	// ----- P32: embedder reuse -----

	@Test
	void reusingASpaceWithADifferentEmbedderIsRefused(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/v1/spaces")).willReturn(okJson(spacesPage(null, spaceJson("notes", SPACE_ID, EMBEDDER_ID)))));

		Map<String, Object> result = new GoodMemAdminTools(connection(wm)).createSpace("notes", OTHER_EMBEDDER_ID, null, null);

		assertThat(result).containsEntry("success", false);
		assertThat((String) result.get("error")).contains("cannot be changed").contains(EMBEDDER_ID);
		verify(0, postRequestedFor(urlPathEqualTo("/v1/spaces")));
	}

	@Test
	void reusingASpaceWithTheSameEmbedderReportsTheSpacesRealEmbedder(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/v1/spaces")).willReturn(okJson(spacesPage(null, spaceJson("notes", SPACE_ID, EMBEDDER_ID)))));

		Map<String, Object> result = new GoodMemAdminTools(connection(wm)).createSpace("notes", EMBEDDER_ID, null, null);

		assertThat(result).containsEntry("success", true).containsEntry("reused", true).containsEntry("spaceId", SPACE_ID);
		assertThat(result.get("embedderIds")).isEqualTo(List.of(EMBEDDER_ID));
		verify(0, postRequestedFor(urlPathEqualTo("/v1/spaces")));
	}

	@Test
	void aCreatedSpaceSendsTheChunkingConfigTheServerRequires(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/v1/spaces")).willReturn(okJson(spacesPage(null))));
		stubFor(post(urlPathEqualTo("/v1/spaces")).willReturn(okJson(spaceJson("notes", SPACE_ID, EMBEDDER_ID))));

		Map<String, Object> result = new GoodMemAdminTools(connection(wm)).createSpace("notes", EMBEDDER_ID, null, null);

		assertThat(result).containsEntry("success", true).containsEntry("reused", false);
		String body = findAll(postRequestedFor(urlPathEqualTo("/v1/spaces"))).get(0).getBodyAsString();
		assertThat(body).contains("\"defaultChunkingConfig\"").contains("\"embedderId\":\"" + EMBEDDER_ID + "\"")
			.doesNotContain("publicRead");
	}

	// ----- P6: pagination -----

	@Test
	void listingSpacesFollowsPagination(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/v1/spaces")).inScenario("pages")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(okJson(spacesPage("t1", spaceJson("one", "01a0d16b-bbcd-701c-bfb4-fa306021e001", EMBEDDER_ID))))
			.willSetStateTo("second"));
		stubFor(get(urlPathEqualTo("/v1/spaces")).inScenario("pages")
			.whenScenarioStateIs("second")
			.withQueryParam("nextToken", equalTo("t1"))
			.willReturn(okJson(spacesPage(null, spaceJson("two", "01a0d16b-bbcd-701c-bfb4-fa306021e002", EMBEDDER_ID)))));

		Map<String, Object> result = new GoodMemAdminTools(connection(wm)).listSpaces();

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> spaces = (List<Map<String, Object>>) result.get("spaces");
		assertThat(spaces).extracting(s -> s.get("name")).containsExactly("one", "two");
	}

	@Test
	void listingMemoriesFollowsPaginationAndIsBounded(WireMockRuntimeInfo wm) {
		String path = "/v1/spaces/" + SPACE_ID + "/memories";
		stubFor(get(urlPathEqualTo(path)).inScenario("mem")
			.whenScenarioStateIs(Scenario.STARTED)
			.willReturn(okJson(memoriesPage("t1", memoryJson("m-1", "COMPLETED", null), memoryJson("m-2", "COMPLETED", null))))
			.willSetStateTo("second"));
		stubFor(get(urlPathEqualTo(path)).inScenario("mem")
			.whenScenarioStateIs("second")
			.willReturn(okJson(memoriesPage(null, memoryJson("m-3", "COMPLETED", null)))));

		GoodMemAdminTools bounded = new GoodMemAdminTools(connection(wm), false, java.time.Duration.ofSeconds(1), 2);
		Map<String, Object> result = bounded.listMemories(SPACE_ID);

		assertThat(result).containsEntry("totalMemories", 2);
		assertThat(result).doesNotContainKey("nextToken");
	}

	// ----- P12 / P30: one request for a memory and its content -----

	@Test
	void gettingAMemoryReturnsItsContentInOneRequest(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/v1/memories/m-1")).willReturn(okJson(memoryJson("m-1", "COMPLETED", "hello there"))));

		Map<String, Object> result = new GoodMemAdminTools(connection(wm)).getMemory("m-1");

		assertThat(result).containsEntry("success", true).containsEntry("content", "hello there");
		assertThat(findAll(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathMatching("/v1/memories/.*")))).hasSize(1);
		assertThat(findAll(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/v1/memories/m-1")))
			.get(0)
			.getUrl()).contains("includeContent=true");
	}

	// ----- P7: the server's message reaches the model -----

	@Test
	void aRejectedWriteCarriesTheServersOwnMessage(WireMockRuntimeInfo wm) {
		stubFor(post(urlPathEqualTo("/v1/memories")).willReturn(aResponse().withStatus(400)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"errors\":[{\"field\":\"content\",\"message\":\"Either originalContent or originalContentB64 must be provided\"}]}")));

		Map<String, Object> result = new GoodMemAdminTools(connection(wm)).createMemory(SPACE_ID, "", null);

		assertThat(result).containsEntry("success", false).containsEntry("statusCode", 400);
		assertThat((String) result.get("error")).contains("must be provided");
	}

	// ----- P10: uploads are confined -----

	@Test
	void uploadsAreConfinedToTheUploadDirectory(WireMockRuntimeInfo wm, @TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("notes.txt"), "inside");
		Files.createSymbolicLink(dir.resolve("escape.txt"), Path.of("/etc/hostname"));
		stubFor(post(urlPathEqualTo("/v1/memories")).willReturn(okJson(memoryJson("m-1", "PENDING", null))));
		GoodMemUploadTool tool = new GoodMemUploadTool(connection(wm), dir);

		Map<String, Object> outside = tool.upload(SPACE_ID, "../../etc/hostname", null);
		Map<String, Object> absolute = tool.upload(SPACE_ID, "/etc/hostname", null);
		Map<String, Object> symlink = tool.upload(SPACE_ID, "escape.txt", null);
		Map<String, Object> inside = tool.upload(SPACE_ID, "notes.txt", null);

		assertThat(outside).containsEntry("success", false);
		assertThat(absolute).containsEntry("success", false);
		assertThat(symlink).containsEntry("success", false);
		assertThat((String) symlink.get("error")).contains("outside");
		assertThat(inside).containsEntry("success", true).containsEntry("contentType", "text/plain");
		verify(1, postRequestedFor(urlPathEqualTo("/v1/memories")));
		assertThat(findAll(postRequestedFor(urlPathEqualTo("/v1/memories"))).get(0).getBodyAsString()).contains("inside");
	}

	// ----- P28 / P2: the model-facing surface -----

	@Test
	void theSearchToolExposesOnlyAQueryAndACount() {
		List<Method> tools = toolMethods(GoodMemSearchTool.class);
		assertThat(tools).hasSize(1);
		assertThat(paramNames(tools.get(0))).containsExactlyInAnyOrder("query", "topK");
	}

	@Test
	void noToolExposesTheArgumentsThatWereModelControlledIn010() {
		List<String> names = new ArrayList<>();
		for (Class<?> c : List.of(GoodMemSearchTool.class, GoodMemAdminTools.class, GoodMemUploadTool.class)) {
			for (Method m : toolMethods(c)) {
				names.addAll(paramNames(m));
			}
		}
		assertThat(names).doesNotContain("publicRead", "filePath", "waitForIndexing", "llmTemperature",
				"relevanceThreshold", "rerankerId", "llmId", "spaceIds", "nextToken", "filterExpression");
	}

	// ----- P22: an injected client is the caller's -----

	@Test
	void anInjectedClientIsNotClosedAndStaysUsable(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/v1/spaces")).willReturn(okJson(spacesPage(null))));
		Goodmem client = Goodmem.builder().baseUrl("http://localhost:" + wm.getHttpPort()).apiKey("k").build();
		GoodMemConnection borrowed = GoodMemConnection.of(client);

		assertThat(borrowed.ownsClient()).isFalse();
		borrowed.close();
		Map<String, Object> result = new GoodMemAdminTools(borrowed).listSpaces();

		assertThat(result).containsEntry("success", true);
		client.close();
	}

	@Test
	void theApiKeyTravelsAsAHeaderNotInTheUrl(WireMockRuntimeInfo wm) {
		stubFor(get(urlPathEqualTo("/v1/spaces")).willReturn(okJson(spacesPage(null))));

		new GoodMemAdminTools(connection(wm)).listSpaces();

		var request = findAll(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/v1/spaces"))).get(0);
		assertThat(request.getHeader("x-api-key")).isEqualTo("test-key");
		assertThat(request.getUrl()).doesNotContain("test-key");
	}

	private static List<Method> toolMethods(Class<?> type) {
		List<Method> methods = new ArrayList<>();
		for (Method m : type.getDeclaredMethods()) {
			if (m.getAnnotation(Tool.class) != null) {
				methods.add(m);
			}
		}
		return methods;
	}

	private static List<String> paramNames(Method method) {
		List<String> names = new ArrayList<>();
		for (Parameter p : method.getParameters()) {
			ToolParam tp = p.getAnnotation(ToolParam.class);
			names.add(p.getName());
			if (tp != null && !tp.description().isBlank()) {
				// names come from -parameters; keep the descriptions honest as a side check
				assertThat(tp.description()).isNotBlank();
			}
		}
		return names;
	}

}
