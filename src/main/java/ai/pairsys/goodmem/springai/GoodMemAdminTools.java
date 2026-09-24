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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.pairsys.goodmem.client.Goodmem;
import ai.pairsys.goodmem.client.Page;
import ai.pairsys.goodmem.client.errors.GoodmemException;
import ai.pairsys.goodmem.client.models.ChunkingConfiguration;
import ai.pairsys.goodmem.client.models.EmbedderId;
import ai.pairsys.goodmem.client.models.JsonMemoryCreationRequest;
import ai.pairsys.goodmem.client.models.LengthMeasurement;
import ai.pairsys.goodmem.client.models.Memory;
import ai.pairsys.goodmem.client.models.MemoryGetOptions;
import ai.pairsys.goodmem.client.models.MemoryProcessingStatus;
import ai.pairsys.goodmem.client.models.RecursiveChunkingConfiguration;
import ai.pairsys.goodmem.client.models.SeparatorKeepStrategy;
import ai.pairsys.goodmem.client.models.Space;
import ai.pairsys.goodmem.client.models.SpaceCreationRequest;
import ai.pairsys.goodmem.client.models.SpaceEmbedder;
import ai.pairsys.goodmem.client.models.SpaceEmbedderConfig;
import ai.pairsys.goodmem.client.models.SpaceListOptions;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.Assert;

/**
 * Management tools: spaces, memories and embedders. These carry the API key's full
 * authority — a model holding them can delete spaces — so give them only to agents that
 * need to administer GoodMem. Reading is {@link GoodMemSearchTool}; uploading files is
 * {@link GoodMemUploadTool}.
 *
 * <p>
 * Differences from the 0.1.0 tools, each of which was reproduced live before being
 * changed: creating a space with a name that exists reuses it <em>only</em> when the
 * embedder matches (it used to report the embedder you asked for while writing into a
 * space built on another); {@code publicRead} is gone (the server rejects it with 400);
 * memory creation waits for indexing instead of searches polling for a minute; listings
 * follow pagination internally instead of handing the model a {@code nextToken}; a
 * memory's content comes back in one request; and the {@code filePath} argument is gone.
 */
public class GoodMemAdminTools {

	private static final Logger logger = LoggerFactory.getLogger(GoodMemAdminTools.class);

	static final int DEFAULT_CHUNK_SIZE = 512;

	static final int DEFAULT_CHUNK_OVERLAP = 64;

	private final GoodMemConnection connection;

	private final boolean waitForIndexing;

	private final Duration indexingTimeout;

	private final int maxListItems;

	public GoodMemAdminTools(GoodMemConnection connection) {
		this(connection, true, Duration.ofSeconds(60), 200);
	}

	/**
	 * @param waitForIndexing whether {@code goodmem_create_memory} waits for the memory
	 * to finish indexing before returning, so a search right after can find it
	 * @param indexingTimeout how long that wait lasts
	 * @param maxListItems the most items a listing tool returns
	 */
	public GoodMemAdminTools(GoodMemConnection connection, boolean waitForIndexing, Duration indexingTimeout,
			int maxListItems) {
		Assert.notNull(connection, "connection cannot be null");
		Assert.notNull(indexingTimeout, "indexingTimeout cannot be null");
		Assert.isTrue(maxListItems > 0, "maxListItems must be positive");
		this.connection = connection;
		this.waitForIndexing = waitForIndexing;
		this.indexingTimeout = indexingTimeout;
		this.maxListItems = maxListItems;
	}

	private Goodmem client() {
		return this.connection.client();
	}

	// ----- Spaces -----

	@Tool(name = "goodmem_create_space",
			description = "Create a GoodMem space, or reuse an existing space of the same name if it uses the same embedder. A space is a container for memories, indexed by one embedder that cannot be changed later.")
	public Map<String, Object> createSpace(@ToolParam(description = "A unique name for the space.") String name,
			@ToolParam(
					description = "The ID of the embedder the space is indexed with. Use goodmem_list_embedders to find one.") String embedderId,
			@ToolParam(required = false,
					description = "Maximum chunk size in characters. Defaults to 512.") @Nullable Integer chunkSize,
			@ToolParam(required = false,
					description = "Overlap between consecutive chunks in characters. Defaults to 64.") @Nullable Integer chunkOverlap) {
		try {
			Space existing = findSpaceByName(name);
			if (existing != null) {
				List<String> embedders = embedderIds(existing);
				if (!embedders.contains(embedderId)) {
					Map<String, Object> result = new LinkedHashMap<>();
					result.put("success", false);
					result.put("error", "A space named '" + name + "' exists but is indexed by embedder(s) " + embedders
							+ ", not " + embedderId
							+ ". An embedder cannot be changed after creation; use that embedder or another name.");
					result.put("spaceId", existing.spaceId().value());
					return result;
				}
				return spaceResult(existing, true);
			}
			int size = (chunkSize != null) ? chunkSize : DEFAULT_CHUNK_SIZE;
			int overlap = (chunkOverlap != null) ? chunkOverlap : DEFAULT_CHUNK_OVERLAP;
			SpaceCreationRequest request = SpaceCreationRequest.builder()
				.name(name)
				.spaceEmbedders(List.of(new SpaceEmbedderConfig(new EmbedderId(embedderId), 1.0)))
				.defaultChunkingConfig(ChunkingConfiguration.recursive(RecursiveChunkingConfiguration.builder()
					.chunkSize(size)
					.chunkOverlap(overlap)
					.keepStrategy(SeparatorKeepStrategy.KEEP_END)
					.lengthMeasurement(LengthMeasurement.CHARACTER_COUNT)
					.build()))
				.build();
			return spaceResult(client().spaces.create(request), false);
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_create_space failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	@Tool(name = "goodmem_list_spaces", description = "List GoodMem spaces with their IDs, names and embedders.")
	public Map<String, Object> listSpaces() {
		try {
			List<Map<String, Object>> spaces = new ArrayList<>();
			for (Space space : allSpaces(null)) {
				spaces.add(spaceSummary(space));
				if (spaces.size() >= this.maxListItems) {
					break;
				}
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("spaces", spaces);
			result.put("totalSpaces", spaces.size());
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_list_spaces failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	@Tool(name = "goodmem_get_space",
			description = "Fetch a GoodMem space by ID, including its embedders, chunking configuration and labels.")
	public Map<String, Object> getSpace(@ToolParam(description = "The UUID of the space.") String spaceId) {
		try {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("space", spaceSummary(client().spaces.get(spaceId)));
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_get_space failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	@Tool(name = "goodmem_delete_space",
			description = "Permanently delete a GoodMem space and every memory in it.")
	public Map<String, Object> deleteSpace(@ToolParam(description = "The UUID of the space to delete.") String spaceId) {
		try {
			client().spaces.delete(spaceId);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("spaceId", spaceId);
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_delete_space failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	// ----- Memories -----

	@Tool(name = "goodmem_create_memory",
			description = "Store a piece of text in a GoodMem space so it can be searched later. Waits for indexing, so a search right after this call can find it.")
	public Map<String, Object> createMemory(
			@ToolParam(description = "The UUID of the space to store the memory in.") String spaceId,
			@ToolParam(description = "The text to store.") String text,
			@ToolParam(required = false,
					description = "Optional metadata as a flat JSON object of string values, e.g. {\"source\":\"meeting\"}.") @Nullable Map<String, Object> metadata) {
		try {
			JsonMemoryCreationRequest.Builder request = JsonMemoryCreationRequest.builder()
				.spaceId(spaceId)
				.originalContent(text)
				.contentType("text/plain");
			if (metadata != null && !metadata.isEmpty()) {
				request.metadata(metadata);
			}
			Memory memory = client().memories.create(request.build());
			String memoryId = memory.memoryId().value();
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("memoryId", memoryId);
			result.put("spaceId", spaceId);
			if (this.waitForIndexing) {
				result.put("status", waitForIndexing(memoryId));
			}
			else {
				result.put("status", String.valueOf(memory.processingStatus()));
			}
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_create_memory failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	@Tool(name = "goodmem_list_memories", description = "List the memories in a GoodMem space.")
	public Map<String, Object> listMemories(@ToolParam(description = "The UUID of the space.") String spaceId) {
		try {
			List<Map<String, Object>> memories = new ArrayList<>();
			Page<Memory> page = client().memories.list(spaceId);
			outer: while (true) {
				for (Memory memory : page.items()) {
					memories.add(memorySummary(memory));
					if (memories.size() >= this.maxListItems) {
						break outer;
					}
				}
				if (!page.hasMore()) {
					break;
				}
				page = page.next();
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("spaceId", spaceId);
			result.put("memories", memories);
			result.put("totalMemories", memories.size());
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_list_memories failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	@Tool(name = "goodmem_get_memory",
			description = "Fetch a GoodMem memory by ID: its metadata, processing status and, for text, its content.")
	public Map<String, Object> getMemory(@ToolParam(description = "The UUID of the memory.") String memoryId) {
		try {
			Memory memory = client().memories.get(memoryId, MemoryGetOptions.builder().includeContent(true).build());
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("memory", memorySummary(memory));
			byte[] content = memory.originalContent();
			if (content != null) {
				String type = memory.contentType() != null ? memory.contentType() : "";
				if (type.startsWith("text/") || type.contains("json")) {
					result.put("content", new String(content, StandardCharsets.UTF_8));
				}
				else {
					result.put("contentBytes", content.length);
				}
			}
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_get_memory failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	@Tool(name = "goodmem_delete_memory", description = "Permanently delete a GoodMem memory.")
	public Map<String, Object> deleteMemory(@ToolParam(description = "The UUID of the memory to delete.") String memoryId) {
		try {
			client().memories.delete(memoryId);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("memoryId", memoryId);
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_delete_memory failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	// ----- Embedders -----

	@Tool(name = "goodmem_list_embedders",
			description = "List the embedders available on this GoodMem server. Use an embedder's ID when creating a space.")
	public Map<String, Object> listEmbedders() {
		try {
			List<Map<String, Object>> embedders = new ArrayList<>();
			// The SDK returns the whole list here; it is not paginated server-side.
			for (var embedder : client().embedders.list()) {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("embedderId", embedder.embedderId() != null ? embedder.embedderId().value() : null);
				entry.put("displayName", embedder.displayName());
				entry.put("modelIdentifier", embedder.modelIdentifier());
				embedders.add(entry);
				if (embedders.size() >= this.maxListItems) {
					break;
				}
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("success", true);
			result.put("embedders", embedders);
			result.put("totalEmbedders", embedders.size());
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_list_embedders failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	// ----- Helpers -----

	/** Every space, following pagination. */
	List<Space> allSpaces(@Nullable String nameFilter) {
		List<Space> spaces = new ArrayList<>();
		SpaceListOptions.Builder options = SpaceListOptions.builder().maxResults(100);
		if (nameFilter != null) {
			options.nameFilter(nameFilter);
		}
		Page<Space> page = client().spaces.list(options.build());
		while (true) {
			spaces.addAll(page.items());
			if (!page.hasMore() || spaces.size() >= 10_000) {
				return spaces;
			}
			page = page.next();
		}
	}

	@Nullable Space findSpaceByName(String name) {
		for (Space space : allSpaces(name)) {
			if (name.equals(space.name())) {
				return space;
			}
		}
		return null;
	}

	static List<String> embedderIds(Space space) {
		List<String> ids = new ArrayList<>();
		if (space.spaceEmbedders() != null) {
			for (SpaceEmbedder embedder : space.spaceEmbedders()) {
				if (embedder.embedderId() != null) {
					ids.add(embedder.embedderId().value());
				}
			}
		}
		return ids;
	}

	String waitForIndexing(String memoryId) {
		long deadline = System.nanoTime() + this.indexingTimeout.toNanos();
		String last = "PENDING";
		while (true) {
			Memory memory = client().memories.get(memoryId);
			MemoryProcessingStatus status = memory.processingStatus();
			last = String.valueOf(status);
			if (status == MemoryProcessingStatus.COMPLETED || status == MemoryProcessingStatus.FAILED) {
				return last;
			}
			if (System.nanoTime() >= deadline) {
				logger.warn("Memory {} was still {} after {}; it was written and may not be searchable yet",
						memoryId, last, this.indexingTimeout);
				return last + " (still indexing after " + this.indexingTimeout.toSeconds() + "s)";
			}
			try {
				Thread.sleep(500);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return last + " (wait interrupted)";
			}
		}
	}

	private static Map<String, Object> spaceResult(Space space, boolean reused) {
		Map<String, Object> result = new LinkedHashMap<>(spaceSummary(space));
		result.put("success", true);
		result.put("reused", reused);
		return result;
	}

	static Map<String, Object> spaceSummary(Space space) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("spaceId", space.spaceId() != null ? space.spaceId().value() : null);
		summary.put("name", space.name());
		summary.put("embedderIds", embedderIds(space));
		if (space.labels() != null && !space.labels().isEmpty()) {
			summary.put("labels", space.labels());
		}
		return summary;
	}

	static Map<String, Object> memorySummary(Memory memory) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("memoryId", memory.memoryId() != null ? memory.memoryId().value() : null);
		summary.put("spaceId", memory.spaceId() != null ? memory.spaceId().value() : null);
		summary.put("contentType", memory.contentType());
		summary.put("processingStatus", String.valueOf(memory.processingStatus()));
		summary.put("metadata", memory.metadata() != null ? memory.metadata() : Map.of());
		return summary;
	}

}
