package ai.pairsys.goodmem.springai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/**
 * Wire-shaped JSON for the mock server. Field sets follow the live capture in
 * {@code retrieve_real.ndjson} (GoodMem v1.0.320); tests override only what matters.
 */
final class Fixtures {

	static final String SPACE_ID = "01a0d16b-bbcd-701c-bfb4-fa306021e078";

	static final String EMBEDDER_ID = "019cfd1c-c033-7517-b7de-f73941a0464b";

	static final String OTHER_EMBEDDER_ID = "019cfd94-2844-7117-85ca-1b9919758a26";

	/** A real vector score from the capture. Negative: pgvector inner product. */
	static final double REAL_VECTOR_SCORE = -0.5911163091659546;

	private static final String ACTOR = "019cfcff-37c7-75ef-be71-06c83dae99c3";

	private Fixtures() {
	}

	static String realCapture() {
		try {
			return Files.readString(Path.of("src/test/resources/retrieve_real.ndjson"), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	static String chunkEvent(String chunkId, String text, String memoryId, double score, int memoryIndex) {
		return """
				{"retrievedItem":{"chunk":{"resultSetId":"01a0d173-3d57-752e-b58c-9925f668806c","chunk":{"chunkId":"%s","memoryId":"%s","chunkSequenceNumber":0,"chunkText":%s,"vectorStatus":"COMPLETED","startOffset":0,"endOffset":40,"createdAt":1790219893735,"updatedAt":1790219895810,"createdById":"%s","updatedById":"%s"},"memoryIndex":%d,"relevanceScore":%s}}}"""
			.formatted(chunkId, memoryId, json(text), ACTOR, ACTOR, memoryIndex, Double.toString(score));
	}

	static String memoryEvent(String memoryId, Map<String, ?> metadata) {
		return """
				{"memoryDefinition":{"memoryId":"%s","spaceId":"%s","originalContentLength":39,"contentType":"text/plain","processingStatus":"COMPLETED","pageImageStatus":"PENDING","pageImageCount":0,"metadata":%s,"createdAt":1790219893735,"updatedAt":1790219895810,"createdById":"%s","updatedById":"%s"}}"""
			.formatted(memoryId, SPACE_ID, jsonObject(metadata), ACTOR, ACTOR);
	}

	/** {@code code == null} omits the field, as a server with a newer code would look after decoding. */
	static String statusEvent(String code, String message) {
		String codeField = (code == null) ? "" : "\"code\":\"" + code + "\",";
		return "{\"status\":{" + codeField + "\"message\":" + json(message) + "}}";
	}

	static String boundary(String kind) {
		return """
				{"resultSetBoundary":{"resultSetId":"01a0d173-3d57-752e-b58c-9925f668806c","kind":"%s","stageName":"retrieve"}}"""
			.formatted(kind);
	}

	static String ndjson(String... events) {
		return String.join("\n", events) + "\n";
	}

	static String spaceJson(String name, String spaceId, String embedderId) {
		return """
				{"spaceId":"%s","name":"%s","labels":{},"spaceEmbedders":[{"spaceId":"%s","embedderId":"%s","defaultRetrievalWeight":1.0,"createdAt":1790219893735,"updatedAt":1790219893735,"createdById":"%s","updatedById":"%s"}],"createdAt":1790219893735,"updatedAt":1790219893735,"ownerId":"019cfcff-37c5-76d0-bd46-8525e29a9c82","createdById":"%s","updatedById":"%s","defaultChunkingConfig":{"recursive":{"chunkSize":512,"chunkOverlap":64,"keepStrategy":"KEEP_END","lengthMeasurement":"CHARACTER_COUNT"}}}"""
			.formatted(spaceId, name, spaceId, embedderId, ACTOR, ACTOR, ACTOR, ACTOR);
	}

	static String spacesPage(String nextToken, String... spaces) {
		String next = (nextToken == null) ? "" : ",\"nextToken\":\"" + nextToken + "\"";
		return "{\"spaces\":[" + String.join(",", spaces) + "]" + next + "}";
	}

	static String memoryJson(String memoryId, String status, String content) {
		String contentField = (content == null) ? ""
				: ",\"originalContent\":\"" + Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))
						+ "\"";
		return """
				{"memoryId":"%s","spaceId":"%s","originalContentLength":11,"contentType":"text/plain","processingStatus":"%s","pageImageStatus":"PENDING","pageImageCount":0,"metadata":{"tag":"a"},"createdAt":1790219893735,"updatedAt":1790219895810,"createdById":"%s","updatedById":"%s"%s}"""
			.formatted(memoryId, SPACE_ID, status, ACTOR, ACTOR, contentField);
	}

	static String memoriesPage(String nextToken, String... memories) {
		String next = (nextToken == null) ? "" : ",\"nextToken\":\"" + nextToken + "\"";
		return "{\"memories\":[" + String.join(",", memories) + "]" + next + "}";
	}

	static String json(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	private static String jsonObject(Map<String, ?> map) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, ?> e : map.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(json(e.getKey())).append(':');
			Object v = e.getValue();
			sb.append((v instanceof Number || v instanceof Boolean) ? String.valueOf(v) : json(String.valueOf(v)));
		}
		return sb.append('}').toString();
	}

}
