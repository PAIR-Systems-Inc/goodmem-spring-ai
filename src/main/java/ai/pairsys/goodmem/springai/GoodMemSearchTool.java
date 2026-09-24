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

import ai.pairsys.goodmem.client.errors.ApiException;
import ai.pairsys.goodmem.client.errors.GoodmemException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.Assert;

/**
 * The one tool an agent needs to read from GoodMem. The model supplies a query and,
 * optionally, how many results it wants; which spaces are searched, whether a reranker
 * runs and any metadata filter are the developer's decisions, fixed on the
 * {@link GoodMemDocumentRetriever} this wraps.
 *
 * <p>
 * The result is a map the model can read directly: {@code results} (chunk text joined
 * to its memory's metadata), {@code partial}, and {@code statuses} when the server
 * reported a problem. A server-reported problem never fails the tool; a transport or
 * API error returns {@code success=false} with the server's own message.
 */
public class GoodMemSearchTool {

	private static final Logger logger = LoggerFactory.getLogger(GoodMemSearchTool.class);

	private final GoodMemDocumentRetriever retriever;

	private final int maxTopK;

	public GoodMemSearchTool(GoodMemDocumentRetriever retriever) {
		this(retriever, 20);
	}

	/**
	 * @param maxTopK the most results the model may ask for in one call
	 */
	public GoodMemSearchTool(GoodMemDocumentRetriever retriever, int maxTopK) {
		Assert.notNull(retriever, "retriever cannot be null");
		Assert.isTrue(maxTopK > 0, "maxTopK must be positive");
		this.retriever = retriever;
		this.maxTopK = maxTopK;
	}

	@Tool(name = "goodmem_search",
			description = "Search long-term memory for passages relevant to a query. Returns the matching text with its metadata and a relevance score (higher is better).")
	public Map<String, Object> search(@ToolParam(description = "What to search for.") String query,
			@ToolParam(required = false,
					description = "How many passages to return. Defaults to the retriever's setting.") @Nullable Integer topK) {
		Map<String, Object> result = new LinkedHashMap<>();
		try {
			RetrievalResults.Outcome outcome = this.retriever.search(query);
			List<Map<String, Object>> results = new ArrayList<>();
			int limit = (topK != null) ? Math.max(1, Math.min(topK, this.maxTopK)) : Integer.MAX_VALUE;
			for (RetrievalResults.Hit hit : outcome.hits()) {
				if (results.size() >= limit) {
					break;
				}
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("text", hit.chunkText());
				entry.put("memoryId", hit.memoryId());
				entry.put("chunkId", hit.chunkId());
				entry.put("score", hit.hostScore());
				entry.put("scoreKind", hit.scoreKind());
				entry.put("metadata", hit.metadata());
				results.add(entry);
			}
			result.put("success", true);
			result.put("results", results);
			result.put("partial", outcome.partial());
			if (!outcome.statuses().isEmpty()) {
				result.put("statuses", outcome.statuses());
			}
			if (outcome.abstractReply() != null) {
				result.put("abstractReply", outcome.abstractReply());
			}
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_search failed: {}", ex.getMessage());
			return failure(ex);
		}
	}

	static Map<String, Object> failure(GoodmemException ex) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("success", false);
		result.put("error", ex.getMessage());
		if (ex instanceof ApiException api) {
			result.put("statusCode", api.getStatusCode());
		}
		return result;
	}

}
