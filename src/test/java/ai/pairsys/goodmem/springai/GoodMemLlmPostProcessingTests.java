package ai.pairsys.goodmem.springai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import static ai.pairsys.goodmem.springai.Fixtures.RERANKER_ID;
import static ai.pairsys.goodmem.springai.Fixtures.SPACE_ID;
import static ai.pairsys.goodmem.springai.Fixtures.boundary;
import static ai.pairsys.goodmem.springai.Fixtures.capture;
import static ai.pairsys.goodmem.springai.Fixtures.ndjson;
import static ai.pairsys.goodmem.springai.GoodMemRerankerFallbackTests.WORKING_RERANKER;
import static ai.pairsys.goodmem.springai.GoodMemRerankerFallbackTests.stubRetrieve;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An LLM the developer sets with {@link GoodMemDocumentRetriever.Builder#llmId}. 0.2.2
 * had no way to request one, so the {@code abstractReply} the search tool already knew
 * how to return could never arrive.
 *
 * <p>
 * The streams were captured from a live GoodMem on 2026-09-29: three memories, query
 * "capital of Jordan". {@code retrieve_llm_reply.ndjson} with a working LLM (OpenRouter
 * qwen3-8b); {@code retrieve_llm_missing.ndjson} with an LLM id that names nothing;
 * {@code retrieve_llm_no_credits.ndjson} with an OpenAI LLM whose account has no
 * credits (HTTP 429); {@code retrieve_reranked_llm_missing.ndjson} with a working
 * reranker and a missing LLM. Each drives the real SDK over WireMock.
 */
@WireMockTest
class GoodMemLlmPostProcessingTests {

	/** The LLM the reply capture used. */
	static final String WORKING_LLM = "019cfd9f-0963-76f9-b069-4cde19a64ba8";

	/** The LLM the missing captures asked for; it does not exist on the server. */
	static final String MISSING_LLM = "00000000-0000-0000-0000-000000000000";

	/** The OpenAI LLM whose account is out of credits. */
	static final String NO_CREDIT_LLM = "019e3f06-e62a-759d-9a88-bb4e375eeb2b";

	static final String CAPTURED_REPLY = "The capital of Jordan is Amman, which is also its largest city. This "
			+ "information is explicitly stated in the retrieved data. Petra, mentioned as an ancient city in southern "
			+ "Jordan, is not the capital.";

	private static final Query QUERY = new Query("capital of Jordan");

	private static final ObjectMapper JSON = new ObjectMapper();

	private static GoodMemConnection connection(WireMockRuntimeInfo wm) {
		return GoodMemConnection.builder().baseUrl("http://localhost:" + wm.getHttpPort()).apiKey("test-key").build();
	}

	static GoodMemDocumentRetriever withLlm(WireMockRuntimeInfo wm, String llmId) {
		return GoodMemDocumentRetriever.builder().connection(connection(wm)).spaceId(SPACE_ID).topK(5).llmId(llmId).build();
	}

	private static String lastRetrieveBody() {
		var requests = findAll(postRequestedFor(urlPathEqualTo("/v1/memories:retrieve")));
		return requests.get(requests.size() - 1).getBodyAsString();
	}

	@SuppressWarnings("unchecked")
	private static List<String> statusCodes(Object statuses) {
		return ((List<Map<String, Object>>) statuses).stream().map(s -> (String) s.get("code")).toList();
	}

	// ----- the request -----

	@Test
	void anLlmIdIsSentInTheChatPostProcessorConfig(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(boundary("BEGIN"), boundary("END")));

		withLlm(wm, WORKING_LLM).retrieve(QUERY);

		assertThat(lastRetrieveBody()).contains("\"postProcessor\"")
			.contains("\"name\":\"" + GoodMemDocumentRetriever.CHAT_POST_PROCESSOR + "\"")
			.contains("\"llm_id\":\"" + WORKING_LLM + "\"")
			.contains("\"max_results\":5")
			.doesNotContain("reranker_id");
	}

	@Test
	void anLlmIdIsSentNextToTheReranker(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(boundary("BEGIN"), boundary("END")));

		GoodMemDocumentRetriever.builder()
			.connection(connection(wm))
			.spaceId(SPACE_ID)
			.rerankerId(RERANKER_ID)
			.llmId(WORKING_LLM)
			.build()
			.retrieve(QUERY);

		assertThat(lastRetrieveBody()).contains("\"reranker_id\":\"" + RERANKER_ID + "\"")
			.contains("\"llm_id\":\"" + WORKING_LLM + "\"");
	}

	@Test
	void withoutAnLlmIdTheRequestIsUnchanged(WireMockRuntimeInfo wm) {
		stubRetrieve(ndjson(boundary("BEGIN"), boundary("END")));

		GoodMemDocumentRetriever.builder().connection(connection(wm)).spaceId(SPACE_ID).llmId(null).build().retrieve(QUERY);
		String plain = lastRetrieveBody();
		GoodMemDocumentRetriever.builder()
			.connection(connection(wm))
			.spaceId(SPACE_ID)
			.rerankerId(RERANKER_ID)
			.build()
			.retrieve(QUERY);
		String reranked = lastRetrieveBody();

		assertThat(plain).doesNotContain("postProcessor").doesNotContain("llm_id");
		assertThat(reranked).contains("\"reranker_id\":\"" + RERANKER_ID + "\"").doesNotContain("llm_id");
	}

	@Test
	void aNonUuidLlmIdIsRefusedAtBuildAndNothingIsSent(WireMockRuntimeInfo wm) {
		assertThatThrownBy(() -> withLlm(wm, "qwen3-8b")).isInstanceOf(GoodMemIds.InvalidIdException.class)
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("llmId must be a UUID")
			.hasMessageNotContaining("qwen3-8b");
		assertThatThrownBy(() -> withLlm(wm, "../llms/" + WORKING_LLM)).isInstanceOf(IllegalArgumentException.class);

		assertThat(findAll(postRequestedFor(urlPathEqualTo("/v1/memories:retrieve")))).isEmpty();
	}

	@Test
	void theLlmIsNotSomethingTheModelCanSet() {
		ToolCallback callback = ToolCallbacks.from(new GoodMemSearchTool(GoodMemDocumentRetriever.builder()
			.connection(GoodMemConnection.builder().baseUrl("http://localhost:1").apiKey("k").build())
			.spaceId(SPACE_ID)
			.llmId(WORKING_LLM)
			.build()))[0];

		String schema = callback.getToolDefinition().inputSchema();

		assertThat(schema).contains("\"query\"").contains("\"topK\"").doesNotContainIgnoringCase("llm");
	}

	// ----- the answer -----

	@Test
	void theAbstractReplyReachesTheSearchTool(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_llm_reply.ndjson"));

		Map<String, Object> result = new GoodMemSearchTool(withLlm(wm, WORKING_LLM)).search("capital of Jordan", null);

		assertThat(result).containsEntry("success", true)
			.containsEntry("partial", false)
			.containsEntry("abstractReply", CAPTURED_REPLY)
			.doesNotContainKey("statuses");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> results = (List<Map<String, Object>>) result.get("results");
		assertThat(results).hasSize(3);
	}

	@Test
	void theAbstractReplyReachesTheModelThroughSpringAisToolCallback(WireMockRuntimeInfo wm) throws Exception {
		stubRetrieve(capture("retrieve_llm_reply.ndjson"));
		ToolCallback callback = ToolCallbacks.from(new GoodMemSearchTool(withLlm(wm, WORKING_LLM)))[0];

		Map<String, Object> result = JSON.readValue(callback.call("{\"query\":\"capital of Jordan\",\"topK\":1}"),
				new TypeReference<Map<String, Object>>() {
				});

		assertThat(result).containsEntry("success", true).containsEntry("abstractReply", CAPTURED_REPLY);
		assertThat((List<?>) result.get("results")).hasSize(1);
	}

	@Test
	void everyDocumentCarriesTheAbstractReply(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_llm_reply.ndjson"));

		List<Document> docs = withLlm(wm, WORKING_LLM).retrieve(QUERY);

		assertThat(docs).hasSize(3)
			.allSatisfy(d -> assertThat(d.getMetadata())
				.containsEntry(GoodMemDocumentRetriever.METADATA_ABSTRACT_REPLY, CAPTURED_REPLY)
				.containsEntry(GoodMemDocumentRetriever.METADATA_PARTIAL, false));
	}

	@Test
	void theAdvisorsPromptGetsTheChunksNotTheReplyWhichStaysInItsDocumentContext(WireMockRuntimeInfo wm) {
		// RetrievalAugmentationAdvisor answers with its own chat model; GoodMem's reply is
		// metadata the application can read back, not a second answer in the prompt.
		stubRetrieve(capture("retrieve_llm_reply.ndjson"));
		AtomicReference<String> prompt = new AtomicReference<>();
		ChatModel model = p -> {
			prompt.set(p.getContents());
			return new ChatResponse(List.of(new Generation(new AssistantMessage("Amman."))));
		};

		ChatClientResponse response = ChatClient.builder(model)
			.build()
			.prompt()
			.advisors(RetrievalAugmentationAdvisor.builder().documentRetriever(withLlm(wm, WORKING_LLM)).build())
			.user("capital of Jordan")
			.call()
			.chatClientResponse();

		assertThat(prompt.get()).contains("Amman is the capital and largest city of Jordan.")
			.doesNotContain(CAPTURED_REPLY);
		@SuppressWarnings("unchecked")
		List<Document> context = (List<Document>) response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
		assertThat(context).isNotEmpty()
			.allSatisfy(d -> assertThat(d.getMetadata())
				.containsEntry(GoodMemDocumentRetriever.METADATA_ABSTRACT_REPLY, CAPTURED_REPLY));
	}

	@Test
	void anLlmDoesNotChangeHowScoresAreReported(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_llm_reply.ndjson"));

		List<Document> docs = withLlm(wm, WORKING_LLM).retrieve(QUERY);

		for (Document doc : docs) {
			double raw = (Double) doc.getMetadata().get("goodmem_raw_score");
			assertThat(doc.getMetadata()).containsEntry("goodmem_score_kind", "vector");
			assertThat(doc.getScore()).isEqualTo(-raw);
		}
		assertThat(docs.get(0).getText()).startsWith("Amman is the capital");
	}

	@Test
	void withoutAnLlmThereIsNoReply(WireMockRuntimeInfo wm) {
		stubRetrieve(Fixtures.realCapture());
		GoodMemDocumentRetriever retriever = GoodMemDocumentRetriever.builder()
			.connection(connection(wm))
			.spaceId(SPACE_ID)
			.build();

		assertThat(new GoodMemSearchTool(retriever).search("x", null)).doesNotContainKey("abstractReply");
		assertThat(retriever.retrieve(new Query("x")))
			.allSatisfy(d -> assertThat(d.getMetadata()).doesNotContainKey(GoodMemDocumentRetriever.METADATA_ABSTRACT_REPLY));
	}

	// ----- an LLM that fails -----

	@Test
	void aMissingLlmIsPartialWithBothStatusesAndEveryHitKept(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_llm_missing.ndjson"));

		Map<String, Object> result = new GoodMemSearchTool(withLlm(wm, MISSING_LLM)).search("capital of Jordan", null);
		List<Document> docs = withLlm(wm, MISSING_LLM).retrieve(QUERY);

		assertThat(result).containsEntry("success", true).containsEntry("partial", true).doesNotContainKey("abstractReply");
		assertThat(statusCodes(result.get("statuses"))).containsExactly("NOT_FOUND", "SUMMARIZATION_FAILED");
		assertThat((List<?>) result.get("results")).hasSize(3);
		assertThat(docs).hasSize(3).allSatisfy(d -> {
			assertThat(d.getMetadata()).containsEntry(GoodMemDocumentRetriever.METADATA_PARTIAL, true)
				.doesNotContainKey(GoodMemDocumentRetriever.METADATA_ABSTRACT_REPLY)
				.containsEntry("goodmem_score_kind", "vector");
			assertThat(statusCodes(d.getMetadata().get(GoodMemDocumentRetriever.METADATA_STATUSES)))
				.containsExactly("NOT_FOUND", "SUMMARIZATION_FAILED");
		});
	}

	@Test
	void anLlmThatFailsAtTheProviderIsPartialWithSummarizationFailed(WireMockRuntimeInfo wm) {
		stubRetrieve(capture("retrieve_llm_no_credits.ndjson"));

		Map<String, Object> result = new GoodMemSearchTool(withLlm(wm, NO_CREDIT_LLM)).search("capital of Jordan", null);

		assertThat(result).containsEntry("success", true).containsEntry("partial", true).doesNotContainKey("abstractReply");
		assertThat(statusCodes(result.get("statuses"))).containsExactly("SUMMARIZATION_FAILED");
		@SuppressWarnings("unchecked")
		Map<String, Object> status = ((List<Map<String, Object>>) result.get("statuses")).get(0);
		assertThat((String) status.get("message")).contains("429");
		assertThat((List<?>) result.get("results")).hasSize(3);
	}

	@Test
	void aMissingLlmLeavesAWorkingRerankersScoresAlone(WireMockRuntimeInfo wm) {
		// Its NOT_FOUND names the LLM (details: llm_id), not the reranker.
		stubRetrieve(capture("retrieve_reranked_llm_missing.ndjson"));

		List<Document> docs = GoodMemDocumentRetriever.builder()
			.connection(connection(wm))
			.spaceId(SPACE_ID)
			.rerankerId(WORKING_RERANKER)
			.llmId(MISSING_LLM)
			.build()
			.retrieve(QUERY);

		assertThat(docs).extracting(d -> d.getMetadata().get("goodmem_score_kind")).containsOnly("reranker");
		assertThat(docs).extracting(Document::getScore).containsExactly(0.91015625, 0.4765625, 0.271484375);
		assertThat(docs).allSatisfy(d -> assertThat(d.getMetadata())
			.containsEntry(GoodMemDocumentRetriever.METADATA_PARTIAL, true));
		assertThat(statusCodes(docs.get(0).getMetadata().get(GoodMemDocumentRetriever.METADATA_STATUSES)))
			.containsExactly("NOT_FOUND", "SUMMARIZATION_FAILED");
	}

}
