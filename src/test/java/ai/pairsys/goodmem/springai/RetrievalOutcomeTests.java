package ai.pairsys.goodmem.springai;

import java.util.Map;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import static ai.pairsys.goodmem.springai.Fixtures.RERANKER_ID;
import static ai.pairsys.goodmem.springai.Fixtures.capture;
import static ai.pairsys.goodmem.springai.Fixtures.chunkEvent;
import static ai.pairsys.goodmem.springai.Fixtures.memoryEvent;
import static ai.pairsys.goodmem.springai.Fixtures.ndjson;
import static ai.pairsys.goodmem.springai.Fixtures.statusEvent;
import static ai.pairsys.goodmem.springai.GoodMemRerankerFallbackTests.MISSING_RERANKER;
import static ai.pairsys.goodmem.springai.GoodMemRerankerFallbackTests.WORKING_RERANKER;
import static ai.pairsys.goodmem.springai.GoodMemRerankerFallbackTests.reranking;
import static ai.pairsys.goodmem.springai.GoodMemRerankerFallbackTests.stubRetrieve;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RetrievalResults.Outcome#reranked()} is decided from the response once the
 * whole stream is in, not from whether a reranker was configured.
 */
@WireMockTest
class RetrievalOutcomeTests {

	@Test
	void aWorkingRerankerYieldsARerankedOutcome(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_reranked.ndjson"));

		RetrievalResults.Outcome outcome = reranking(wm, WORKING_RERANKER).search("capital of Jordan");

		assertThat(outcome.reranked()).isTrue();
		assertThat(outcome.partial()).isFalse();
		assertThat(outcome.hits()).extracting(RetrievalResults.Hit::scoreKind).containsOnly("reranker");
	}

	@Test
	void aFailedRerankerYieldsAVectorOutcomeThatIsStillPartial(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_reranker_failed.ndjson"));

		RetrievalResults.Outcome outcome = reranking(wm, MISSING_RERANKER).search("capital of Jordan");

		assertThat(outcome.reranked()).isFalse();
		assertThat(outcome.partial()).isTrue();
		assertThat(outcome.hits()).hasSize(3).extracting(RetrievalResults.Hit::scoreKind).containsOnly("vector");
	}

	@Test
	void anUnrelatedStatusLeavesTheOutcomeReranked(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(statusEvent("SOMETHING_FROM_A_NEWER_SERVER", "odd"), memoryEvent("m-1", Map.of()),
				chunkEvent("c-1", "alpha", "m-1", 0.87, 0)));

		RetrievalResults.Outcome outcome = reranking(wm, RERANKER_ID).search("alpha");

		assertThat(outcome.reranked()).isTrue();
		assertThat(outcome.partial()).isTrue();
	}

	@Test
	void noRerankerRequestedIsNeverReranked(WireMockRuntimeInfo wm) {
		stubRetrieve(Fixtures.realCapture());

		RetrievalResults.Outcome outcome = GoodMemDocumentRetriever.builder()
			.connection(GoodMemConnection.builder()
				.baseUrl("http://localhost:" + wm.getHttpPort())
				.apiKey("test-key")
				.build())
			.spaceId(Fixtures.SPACE_ID)
			.build()
			.search("who wrote the first algorithm");

		assertThat(outcome.reranked()).isFalse();
		assertThat(outcome.hits()).isNotEmpty().extracting(RetrievalResults.Hit::scoreKind).containsOnly("vector");
	}

}
