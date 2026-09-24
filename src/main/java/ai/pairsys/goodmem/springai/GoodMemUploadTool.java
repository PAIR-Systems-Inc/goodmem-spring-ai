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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import ai.pairsys.goodmem.client.errors.GoodmemException;
import ai.pairsys.goodmem.client.models.JsonMemoryCreationRequest;
import ai.pairsys.goodmem.client.models.Memory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.Assert;

/**
 * Lets an agent upload a file from one directory the developer chose.
 *
 * <p>
 * 0.1.0's {@code goodmem_create_memory} took a {@code filePath} from the model with no
 * restriction; a live probe read {@code /etc/hostname} off the machine and uploaded it.
 * This tool exists only when an upload directory is configured, takes a name relative
 * to it, and refuses anything — including a symlink — that resolves outside it.
 */
public class GoodMemUploadTool {

	private static final Logger logger = LoggerFactory.getLogger(GoodMemUploadTool.class);

	private final GoodMemConnection connection;

	private final Path uploadDir;

	public GoodMemUploadTool(GoodMemConnection connection, Path uploadDir) {
		Assert.notNull(connection, "connection cannot be null");
		Assert.notNull(uploadDir, "uploadDir cannot be null");
		Assert.isTrue(Files.isDirectory(uploadDir), "uploadDir must be an existing directory: " + uploadDir);
		this.connection = connection;
		this.uploadDir = uploadDir.toAbsolutePath().normalize();
	}

	@Tool(name = "goodmem_upload_file",
			description = "Upload a file from the agent's upload directory into a GoodMem space. The name is relative to that directory.")
	public Map<String, Object> upload(@ToolParam(description = "The UUID of the space to store it in.") String spaceId,
			@ToolParam(description = "The file name, relative to the upload directory.") String fileName,
			@ToolParam(required = false,
					description = "Optional metadata as a flat JSON object of string values.") @Nullable Map<String, Object> metadata) {
		Map<String, Object> result = new LinkedHashMap<>();
		Path resolved;
		try {
			resolved = resolveInside(fileName);
		}
		catch (IllegalArgumentException | IOException ex) {
			result.put("success", false);
			result.put("error", ex.getMessage());
			return result;
		}
		try {
			byte[] bytes = Files.readAllBytes(resolved);
			String contentType = contentTypeOf(resolved);
			JsonMemoryCreationRequest.Builder request = JsonMemoryCreationRequest.builder()
				.spaceId(spaceId)
				.contentType(contentType);
			if (contentType.startsWith("text/")) {
				request.originalContent(new String(bytes, StandardCharsets.UTF_8));
			}
			else {
				request.originalContentBytes(bytes);
			}
			if (metadata != null && !metadata.isEmpty()) {
				request.metadata(metadata);
			}
			Memory memory = this.connection.client().memories.create(request.build());
			result.put("success", true);
			result.put("memoryId", memory.memoryId().value());
			result.put("spaceId", spaceId);
			result.put("contentType", contentType);
			result.put("status", String.valueOf(memory.processingStatus()));
			return result;
		}
		catch (IOException ex) {
			result.put("success", false);
			result.put("error", "Failed to read " + fileName + ": " + ex.getMessage());
			return result;
		}
		catch (GoodmemException ex) {
			logger.warn("goodmem_upload_file failed: {}", ex.getMessage());
			return GoodMemSearchTool.failure(ex);
		}
	}

	/**
	 * Resolve a model-supplied name to a real file inside the upload directory. Symlinks
	 * are followed before the containment check, so a link pointing outside is refused.
	 */
	Path resolveInside(String fileName) throws IOException {
		if (fileName == null || fileName.isBlank()) {
			throw new IllegalArgumentException("fileName is required");
		}
		Path candidate = this.uploadDir.resolve(fileName).normalize();
		if (!candidate.startsWith(this.uploadDir)) {
			throw new IllegalArgumentException("'" + fileName + "' is outside the upload directory");
		}
		if (!Files.exists(candidate)) {
			throw new IllegalArgumentException("'" + fileName + "' does not exist in the upload directory");
		}
		Path real = candidate.toRealPath();
		if (!real.startsWith(this.uploadDir.toRealPath())) {
			throw new IllegalArgumentException("'" + fileName + "' resolves outside the upload directory");
		}
		if (!Files.isRegularFile(real)) {
			throw new IllegalArgumentException("'" + fileName + "' is not a regular file");
		}
		return real;
	}

	static String contentTypeOf(Path path) {
		String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
		if (lower.endsWith(".pdf")) {
			return "application/pdf";
		}
		if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
			return "text/markdown";
		}
		if (lower.endsWith(".txt") || lower.endsWith(".log")) {
			return "text/plain";
		}
		if (lower.endsWith(".html") || lower.endsWith(".htm")) {
			return "text/html";
		}
		if (lower.endsWith(".csv")) {
			return "text/csv";
		}
		if (lower.endsWith(".json")) {
			return "application/json";
		}
		try {
			String probed = Files.probeContentType(path);
			if (probed != null) {
				return probed;
			}
		}
		catch (IOException ignored) {
			// fall through
		}
		return "application/octet-stream";
	}

}
