/*
 * Copyright 2026 PAIR Systems, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.pairsys.goodmem.springai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.pairsys.goodmem.client.RetrieveMemoryStream;
import ai.pairsys.goodmem.client.models.PostProcessor;
import ai.pairsys.goodmem.client.models.RetrieveMemoryRequest;
import ai.pairsys.goodmem.client.models.SpaceId;
import ai.pairsys.goodmem.client.models.SpaceKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.util.Assert;

/**
 * A Spring AI {@link DocumentRetriever} backed by GoodMem, for use with
 * {@code RetrievalAugmentationAdvisor} and any other RAG pipeline that takes one.
 *
 * <p>
 * The spaces, reranker and metadata filter are fixed by the developer at construction;
 * the query is the only thing that varies per call. GoodMem embeds server-side, so no
 * embedding model is needed here.
 *
 * <p>
 * Every returned {@link Document} carries {@code goodmem_partial} (whether the server
 * reported a problem) and, when it did, {@code goodmem_statuses}. A search that
 * reported a problem and found nothing returns an empty list and logs a warning; it
 * never throws for a server-reported status. {@link Document#getScore()} is higher-is-
 * better: vector scores are negated from GoodMem's negative inner product, reranker
 * scores are passed through (their range is provider-dependent, not 0–1). The raw
 * value is kept in {@code goodmem_raw_score} beside {@code goodmem_score_kind}.
 */
public final class GoodMemDocumentRetriever implements DocumentRetriever {

	private static final Logger logger = LoggerFactory.getLogger(GoodMemDocumentRetriever.class);

	static final String CHAT_POST_PROCESSOR = "com.goodmem.retrieval.postprocess.ChatPostProcessorFactory";

	public static final String METADATA_PARTIAL = "goodmem_partial";

	public static final String METADATA_STATUSES = "goodmem_statuses";

	private final GoodMemConnection connection;

	private final List<String> spaceIds;

	private final int topK;

	private final @Nullable String rerankerId;

	private final @Nullable String filter;

	private GoodMemDocumentRetriever(Builder builder) {
		Assert.notNull(builder.connection, "connection cannot be null");
		Assert.notEmpty(builder.spaceIds, "at least one spaceId is required");
		Assert.isTrue(builder.topK > 0, "topK must be positive");
		this.connection = builder.connection;
		this.spaceIds = List.copyOf(builder.spaceIds);
		this.topK = builder.topK;
		this.rerankerId = builder.rerankerId;
		this.filter = (builder.filter != null && !builder.filter.isBlank()) ? builder.filter : null;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public List<Document> retrieve(Query query) {
		Assert.notNull(query, "query cannot be null");
		RetrievalResults.Outcome outcome = search(query.text());
		List<Document> documents = new ArrayList<>(outcome.hits().size());
		for (RetrievalResults.Hit hit : outcome.hits()) {
			Map<String, Object> metadata = new LinkedHashMap<>(hit.metadata());
			metadata.put("goodmem_memory_id", hit.memoryId());
			metadata.put("goodmem_chunk_id", hit.chunkId());
			if (hit.spaceId() != null) {
				metadata.put("goodmem_space_id", hit.spaceId());
			}
			if (hit.source() != null) {
				metadata.put("source", hit.source());
			}
			metadata.put("goodmem_score_kind", hit.scoreKind());
			if (hit.rawScore() != null) {
				metadata.put("goodmem_raw_score", hit.rawScore());
			}
			metadata.put(METADATA_PARTIAL, outcome.partial());
			if (!outcome.statuses().isEmpty()) {
				metadata.put(METADATA_STATUSES, outcome.statuses());
			}
			Document.Builder document = Document.builder()
				.id(hit.chunkId() != null ? hit.chunkId() : hit.memoryId())
				.text(hit.chunkText())
				.metadata(metadata);
			if (hit.hostScore() != null) {
				document.score(hit.hostScore());
			}
			documents.add(document.build());
		}
		return documents;
	}

	/** The raw outcome, for callers that want statuses without Documents. */
	RetrievalResults.Outcome search(String text) {
		List<SpaceKey> keys = new ArrayList<>();
		for (String spaceId : this.spaceIds) {
			keys.add(new SpaceKey(new SpaceId(spaceId), null, this.filter));
		}
		RetrieveMemoryRequest.Builder request = RetrieveMemoryRequest.builder()
			.message(text)
			.spaceKeys(keys)
			.requestedSize(this.topK)
			.fetchMemory(true);
		if (this.rerankerId != null) {
			request.postProcessor(new PostProcessor(CHAT_POST_PROCESSOR,
					Map.of("reranker_id", this.rerankerId, "max_results", this.topK)));
		}
		RetrieveMemoryStream stream = this.connection.client().memories.retrieve(request.build());
		RetrievalResults.Outcome outcome = RetrievalResults.collect(stream, this.rerankerId != null);
		if (outcome.partial() && outcome.hits().isEmpty()) {
			// Contract: a problem with no results is empty plus a flag, never an exception.
			logger.warn("GoodMem reported a problem and returned no results for spaces {}: {}", this.spaceIds,
					outcome.statuses());
		}
		return outcome;
	}

	public static final class Builder {

		private @Nullable GoodMemConnection connection;

		private List<String> spaceIds = new ArrayList<>();

		private int topK = 5;

		private @Nullable String rerankerId;

		private @Nullable String filter;

		private Builder() {
		}

		public Builder connection(GoodMemConnection connection) {
			this.connection = connection;
			return this;
		}

		public Builder spaceId(String spaceId) {
			this.spaceIds = List.of(spaceId);
			return this;
		}

		public Builder spaceIds(List<String> spaceIds) {
			this.spaceIds = new ArrayList<>(spaceIds);
			return this;
		}

		public Builder topK(int topK) {
			this.topK = topK;
			return this;
		}

		public Builder rerankerId(@Nullable String rerankerId) {
			this.rerankerId = rerankerId;
			return this;
		}

		/**
		 * A GoodMem filter expression applied to every space. Build it with
		 * {@link GoodMemFilters} rather than by string interpolation.
		 */
		public Builder filter(@Nullable String filter) {
			this.filter = filter;
			return this;
		}

		public GoodMemDocumentRetriever build() {
			return new GoodMemDocumentRetriever(this);
		}

	}

}
