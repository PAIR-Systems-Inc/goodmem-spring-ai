package ai.pairsys.goodmem.springai;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;

import static ai.pairsys.goodmem.springai.Fixtures.RERANKER_ID;
import static ai.pairsys.goodmem.springai.Fixtures.SPACE_ID;
import static ai.pairsys.goodmem.springai.Fixtures.capture;
import static ai.pairsys.goodmem.springai.Fixtures.chunkEvent;
import static ai.pairsys.goodmem.springai.Fixtures.memoryEvent;
import static ai.pairsys.goodmem.springai.Fixtures.ndjson;
import static ai.pairsys.goodmem.springai.Fixtures.statusEvent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A configured reranker that fails. GoodMem v1.0.320 then reports
 * {@code RERANKING_FAILED} (and {@code NOT_FOUND} naming a missing reranker) and still
 * returns the vector-stage hits, scored as negative distances. 0.2.1 labelled them
 * {@code reranker} from configuration and passed the distances through un-negated, so
 * the worst match scored highest.
 *
 * <p>
 * {@code retrieve_reranker_failed.ndjson} and {@code retrieve_reranked.ndjson} were
 * captured from a live v1.0.320 on 2026-09-28: three memories, query "capital of
 * Jordan", a missing and a working reranker. {@code retrieve_degraded_hits.ndjson} is
 * the stream the CAMEL fix was measured with. Each drives the real SDK over WireMock.
 */
@WireMockTest
class GoodMemRerankerFallbackTests {

	/** The reranker the failed capture asked for; it does not exist on the server. */
	static final String MISSING_RERANKER = "00000000-0000-7000-8000-000000000000";

	/** The reranker the working capture used. */
	static final String WORKING_RERANKER = "019e6da0-8a5a-72b0-8656-04dddfb25762";

	private static final Query QUERY = new Query("capital of Jordan");

	static GoodMemDocumentRetriever reranking(WireMockRuntimeInfo wm, String rerankerId) {
		GoodMemConnection connection = GoodMemConnection.builder()
			.baseUrl("http://localhost:" + wm.getHttpPort())
			.apiKey("test-key")
			.build();
		return GoodMemDocumentRetriever.builder()
			.connection(connection)
			.spaceId(SPACE_ID)
			.topK(5)
			.rerankerId(rerankerId)
			.build();
	}

	static void stubRetrieve(String body) {
		stubFor(post(urlPathEqualTo("/v1/memories:retrieve"))
			.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/x-ndjson").withBody(body)));
	}

	@SuppressWarnings("unchecked")
	private static List<String> statusCodes(Object statuses) {
		return ((List<Map<String, Object>>) statuses).stream().map(s -> (String) s.get("code")).toList();
	}

	// ----- the fallback is vector-scored -----

	@Test
	void aFailedRerankersFallbackHitsAreVectorScoredAndNegated(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_reranker_failed.ndjson"));

		List<Document> docs = reranking(wm, MISSING_RERANKER).retrieve(QUERY);

		assertThat(docs).hasSize(3);
		for (Document doc : docs) {
			double raw = (Double) doc.getMetadata().get("goodmem_raw_score");
			assertThat(raw).isNegative();
			assertThat(doc.getMetadata()).containsEntry("goodmem_score_kind", "vector");
			assertThat(doc.getScore()).isEqualTo(-raw);
		}
		// The server sends the best match first; higher-is-better scores must agree.
		assertThat(docs).extracting(Document::getScore).isSortedAccordingTo(Comparator.reverseOrder());
		assertThat(docs.get(0).getText()).startsWith("Amman is the capital");
	}

	@Test
	void theFallbackKeepsTheBestMatchFirstThroughSpringAisDocumentJoiner(WireMockRuntimeInfo wm) {
		// RetrievalAugmentationAdvisor's default joiner sorts by Document.getScore(), highest
		// first. With the distances un-negated it put the photosynthesis memory ahead of Amman.
		stubRetrieve(capture("retrieve_reranker_failed.ndjson"));

		List<Document> docs = reranking(wm, MISSING_RERANKER).retrieve(QUERY);
		List<Document> joined = new ConcatenationDocumentJoiner().join(Map.of(QUERY, List.of(docs)));

		// The chunker keeps each memory's trailing newline.
		assertThat(joined).extracting(d -> d.getText().strip())
			.containsExactly("Amman is the capital and largest city of Jordan.",
					"Petra is an ancient city in southern Jordan, carved into rose-red sandstone cliffs.",
					"Photosynthesis converts sunlight, water and carbon dioxide into sugar.");
	}

	@Test
	void aRerankerThresholdKeyedOnScoreKindLeavesTheFallbackAlone(WireMockRuntimeInfo wm) {
		// The connector sets no threshold; a caller thresholds reranker scores downstream, by
		// goodmem_score_kind as the README says. On the fallback that removed every hit.
		stubRetrieve(capture("retrieve_reranker_failed.ndjson"));
		DocumentPostProcessor rerankerThreshold = (query, documents) -> documents.stream()
			.filter(d -> !"reranker".equals(d.getMetadata().get("goodmem_score_kind")) || d.getScore() >= 0.3)
			.toList();

		List<Document> kept = rerankerThreshold.process(QUERY, reranking(wm, MISSING_RERANKER).retrieve(QUERY));

		assertThat(kept).hasSize(3);
	}

	@Test
	void theCapturedDegradedStreamIsVectorScoredThroughTheSearchTool(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_degraded_hits.ndjson"));

		Map<String, Object> result = new GoodMemSearchTool(reranking(wm, MISSING_RERANKER)).search("canary", null);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
		assertThat(results).hasSize(1);
		assertThat(results.get(0)).containsEntry("scoreKind", "vector");
		assertThat((Double) results.get(0).get("score")).isCloseTo(0.5845972299575806, within(1e-12));
		assertThat(result).containsEntry("success", true).containsEntry("partial", true);
		assertThat(statusCodes(result.get("statuses"))).containsExactly("NOT_FOUND", "RERANKING_FAILED");
	}

	@Test
	void aRerankingFailedThatArrivesAfterTheHitsStillCounts(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(memoryEvent("m-1", Map.of()), chunkEvent("c-1", "alpha", "m-1", -0.2715, 0),
				statusEvent("RERANKING_FAILED", "boom")));

		Document doc = reranking(wm, RERANKER_ID).retrieve(new Query("alpha")).get(0);

		assertThat(doc.getMetadata()).containsEntry("goodmem_score_kind", "vector")
			.containsEntry(GoodMemDocumentRetriever.METADATA_PARTIAL, true);
		assertThat(doc.getScore()).isEqualTo(0.2715);
	}

	@Test
	void aRerankerNotFoundAloneMeansTheHitsAreNotReranked(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(
				statusEvent("NOT_FOUND", "Reranker validation failed: Exception: Reranker not found: " + RERANKER_ID,
						Map.of("reranker_id", RERANKER_ID)),
				memoryEvent("m-1", Map.of()), chunkEvent("c-1", "alpha", "m-1", -0.5947, 0)));

		Document doc = reranking(wm, RERANKER_ID).retrieve(new Query("alpha")).get(0);

		assertThat(doc.getMetadata()).containsEntry("goodmem_score_kind", "vector");
		assertThat(doc.getScore()).isEqualTo(0.5947);
	}

	@Test
	void aRerankerNotFoundIsRecognisedByACamelCaseDetailOrByItsMessage(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(statusEvent("NOT_FOUND", "not found", Map.of("rerankerId", RERANKER_ID)),
				memoryEvent("m-1", Map.of()), chunkEvent("c-1", "alpha", "m-1", -0.4, 0)));
		Document byDetail = reranking(wm, RERANKER_ID).retrieve(new Query("alpha")).get(0);

		stubRetrieve(ndjson(statusEvent("NOT_FOUND", "Reranker not found"), memoryEvent("m-1", Map.of()),
				chunkEvent("c-1", "alpha", "m-1", -0.4, 0)));
		Document byMessage = reranking(wm, RERANKER_ID).retrieve(new Query("alpha")).get(0);

		assertThat(byDetail.getMetadata()).containsEntry("goodmem_score_kind", "vector");
		assertThat(byMessage.getMetadata()).containsEntry("goodmem_score_kind", "vector");
		assertThat(byDetail.getScore()).isEqualTo(0.4);
		assertThat(byMessage.getScore()).isEqualTo(0.4);
	}

	// ----- what must not change -----

	@Test
	void theFallbackIsPartialWithBothStatusesAndEveryHitKept(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_reranker_failed.ndjson"));

		List<Document> docs = reranking(wm, MISSING_RERANKER).retrieve(QUERY);

		assertThat(docs).hasSize(3);
		for (Document doc : docs) {
			assertThat(doc.getMetadata()).containsEntry(GoodMemDocumentRetriever.METADATA_PARTIAL, true);
			// FEATURE_DISABLED is in the stream too; it is a notice and stays out.
			assertThat(statusCodes(doc.getMetadata().get(GoodMemDocumentRetriever.METADATA_STATUSES)))
				.containsExactly("NOT_FOUND", "RERANKING_FAILED");
		}
	}

	@Test
	void anUnrelatedStatusKeepsRerankerScores(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(statusEvent("SOMETHING_FROM_A_NEWER_SERVER", "odd"), memoryEvent("m-1", Map.of()),
				chunkEvent("c-1", "alpha", "m-1", 0.87, 0)));

		Map<String, Object> result = new GoodMemSearchTool(reranking(wm, RERANKER_ID)).search("alpha", null);

		@SuppressWarnings("unchecked")
		Map<String, Object> hit = ((List<Map<String, Object>>) result.get("results")).get(0);
		assertThat(hit).containsEntry("scoreKind", "reranker").containsEntry("score", 0.87);
		assertThat(result).containsEntry("partial", true);
		assertThat(statusCodes(result.get("statuses"))).containsExactly("UNKNOWN");
	}

	@Test
	void aNotFoundAboutSomethingElseKeepsRerankerScores(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(statusEvent("NOT_FOUND", "Space not found", Map.of("space_id", SPACE_ID)),
				memoryEvent("m-1", Map.of()), chunkEvent("c-1", "alpha", "m-1", 0.87, 0)));

		Document doc = reranking(wm, RERANKER_ID).retrieve(new Query("alpha")).get(0);

		assertThat(doc.getMetadata()).containsEntry("goodmem_score_kind", "reranker")
			.containsEntry(GoodMemDocumentRetriever.METADATA_PARTIAL, true);
		assertThat(doc.getScore()).isEqualTo(0.87);
	}

	@Test
	void aWorkingRerankerIsUnchanged(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_reranked.ndjson"));

		List<Document> docs = reranking(wm, WORKING_RERANKER).retrieve(QUERY);

		assertThat(docs).extracting(d -> d.getMetadata().get("goodmem_score_kind")).containsOnly("reranker");
		assertThat(docs).extracting(Document::getScore).containsExactly(0.91015625, 0.4765625, 0.271484375);
		assertThat(docs).allSatisfy(d -> assertThat(d.getMetadata())
			.containsEntry(GoodMemDocumentRetriever.METADATA_PARTIAL, false)
			.doesNotContainKey(GoodMemDocumentRetriever.METADATA_STATUSES));
		assertThat(docs.get(0).getText()).startsWith("Amman is the capital");
	}

}
