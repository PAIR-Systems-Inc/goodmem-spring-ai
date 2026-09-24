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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ai.pairsys.goodmem.client.RetrieveMemoryStream;
import ai.pairsys.goodmem.client.errors.GoodmemException;
import ai.pairsys.goodmem.client.models.ChunkReference;
import ai.pairsys.goodmem.client.models.GoodMemStatus;
import ai.pairsys.goodmem.client.models.GoodMemStatusCode;
import ai.pairsys.goodmem.client.models.Memory;
import ai.pairsys.goodmem.client.models.MemoryChunkResponse;
import ai.pairsys.goodmem.client.models.RetrieveMemoryEvent;
import org.jspecify.annotations.Nullable;

/**
 * Turns a retrieval stream into hits and statuses, following the retrieval status
 * contract every GoodMem integration shares.
 *
 * <ul>
 * <li>{@code FEATURE_DISABLED} and {@code LLM_CAPABILITY_INFERRED} are notices, by code
 * alone.</li>
 * <li>A code this SDK does not know decodes as {@code null}; it is reported as
 * {@code UNKNOWN} and marks the result partial, and never discards hits.</li>
 * <li>A problem with hits returns the hits, flagged. A problem without hits returns
 * empty, flagged. Neither throws.</li>
 * <li>A stream that breaks mid-way keeps what arrived and reports
 * {@code MALFORMED_STREAM}.</li>
 * </ul>
 */
final class RetrievalResults {

	static final String MALFORMED_STREAM = "MALFORMED_STREAM";

	private RetrievalResults() {
	}

	/** One retrieved chunk joined to its memory. */
	record Hit(String chunkId, String chunkText, String memoryId, @Nullable String spaceId,
			@Nullable String source, Map<String, Object> metadata, @Nullable Double rawScore, String scoreKind) {

		/**
		 * The score under a higher-is-better convention. A GoodMem vector score is a
		 * pgvector negative inner product — the best match is the lowest number — so it
		 * is negated. A reranker score is already higher-is-better and is passed through;
		 * its range is provider-dependent, not 0–1.
		 */
		@Nullable Double hostScore() {
			if (this.rawScore == null) {
				return null;
			}
			return "reranker".equals(this.scoreKind) ? this.rawScore : -this.rawScore;
		}
	}

	/** Everything a caller needs to report a retrieval honestly. */
	record Outcome(List<Hit> hits, List<Map<String, Object>> statuses, boolean partial,
			@Nullable String abstractReply) {
	}

	/** Drain a stream, keeping every event that arrived before any break. */
	static Outcome collect(RetrieveMemoryStream stream, boolean reranked) {
		List<RetrieveMemoryEvent> events = new ArrayList<>();
		String truncated = null;
		try (stream) {
			for (RetrieveMemoryEvent event : stream) {
				events.add(event);
			}
		}
		catch (GoodmemException ex) {
			if (events.isEmpty()) {
				throw ex;
			}
			truncated = ex.getMessage();
		}
		return outcome(events, reranked, truncated);
	}

	static Outcome outcome(List<RetrieveMemoryEvent> events, boolean reranked, @Nullable String truncated) {
		List<Map<String, Object>> statuses = new ArrayList<>();
		boolean partial = false;
		for (RetrieveMemoryEvent event : events) {
			GoodMemStatus status = event.status();
			if (status == null || isInformational(status)) {
				continue;
			}
			Map<String, Object> entry = new LinkedHashMap<>();
			GoodMemStatusCode code = status.code();
			if (code == null || code == GoodMemStatusCode.UNKNOWN) {
				entry.put("code", "UNKNOWN");
				entry.put("unrecognized", true);
			}
			else {
				entry.put("code", code.name());
			}
			entry.put("message", status.message());
			if (status.details() != null && !status.details().isEmpty()) {
				entry.put("details", status.details());
			}
			statuses.add(entry);
			partial = true;
		}
		if (truncated != null) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("code", MALFORMED_STREAM);
			entry.put("message", truncated);
			statuses.add(entry);
			partial = true;
		}

		String abstractReply = null;
		for (RetrieveMemoryEvent event : events) {
			if (event.abstractReply() != null && event.abstractReply().text() != null) {
				abstractReply = event.abstractReply().text();
				break;
			}
		}
		return new Outcome(hits(events, reranked), statuses, partial, abstractReply);
	}

	static boolean isInformational(GoodMemStatus status) {
		GoodMemStatusCode code = status.code();
		return code == GoodMemStatusCode.FEATURE_DISABLED || code == GoodMemStatusCode.LLM_CAPABILITY_INFERRED;
	}

	/**
	 * Join chunks to their memory definitions <em>by UUID</em>, ignoring event order and
	 * the positional {@code memoryIndex}. Two chunks of one memory are two hits; the same
	 * chunk twice is one.
	 */
	static List<Hit> hits(List<RetrieveMemoryEvent> events, boolean reranked) {
		Map<String, Memory> memories = new LinkedHashMap<>();
		for (RetrieveMemoryEvent event : events) {
			Memory memory = event.memoryDefinition();
			if (memory != null && memory.memoryId() != null) {
				memories.put(memory.memoryId().value(), memory);
			}
		}
		List<Hit> hits = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (RetrieveMemoryEvent event : events) {
			if (event.retrievedItem() == null || event.retrievedItem().chunk() == null) {
				continue;
			}
			ChunkReference reference = event.retrievedItem().chunk();
			MemoryChunkResponse chunk = reference.chunk();
			if (chunk == null || chunk.chunkText() == null || chunk.chunkText().isBlank()) {
				continue;
			}
			String chunkId = chunk.chunkId() != null ? chunk.chunkId().value() : null;
			String memoryId = chunk.memoryId() != null ? chunk.memoryId().value() : null;
			if (chunkId != null && !seen.add(chunkId)) {
				continue;
			}
			Memory memory = memoryId != null ? memories.get(memoryId) : null;
			if (memory == null && event.retrievedItem().memory() != null) {
				memory = event.retrievedItem().memory();
			}
			Map<String, Object> metadata = new LinkedHashMap<>();
			if (memory != null && memory.metadata() != null) {
				metadata.putAll(memory.metadata());
			}
			String spaceId = (memory != null && memory.spaceId() != null) ? memory.spaceId().value() : null;
			String source = (memory != null && memory.originalContentRef() != null) ? memory.originalContentRef()
					: memoryId;
			hits.add(new Hit(chunkId, chunk.chunkText(), memoryId, spaceId, source, metadata,
					reference.relevanceScore(), reranked ? "reranker" : "vector"));
		}
		return hits;
	}

}
