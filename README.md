# spring-ai-goodmem

[GoodMem](https://goodmem.ai) integration for [Spring AI](https://docs.spring.io/spring-ai/reference/).

GoodMem gives AI agents retrieval-augmented generation (RAG) memory. Store
documents in a space and GoodMem chunks, embeds, and indexes them so your
agent can pull back the most relevant passages on any question.

This package exposes the GoodMem API as Spring AI tools. Register them with
a `ChatClient` and the model can store, retrieve, and inspect memories on a
running GoodMem server.

## Installation

```xml
<dependency>
    <groupId>ai.goodmem</groupId>
    <artifactId>spring-ai-goodmem</artifactId>
    <version>0.1.0</version>
</dependency>
```

Spring AI 1.0 or later is required and must be on the classpath.

## Quickstart

```java
import ai.goodmem.springai.GoodMemClient;
import ai.goodmem.springai.GoodMemTools;
import org.springframework.ai.chat.client.ChatClient;

GoodMemClient client = GoodMemClient.builder()
    .baseUrl(System.getenv("GOODMEM_BASE_URL"))
    .apiKey(System.getenv("GOODMEM_API_KEY"))
    .verifySsl(false) // self-signed cert in local dev
    .build();

GoodMemTools tools = new GoodMemTools(client);

String reply = ChatClient.builder(chatModel)
    .defaultSystem("You have access to a GoodMem semantic memory store. "
        + "Use the goodmem tools to store facts the user shares and to "
        + "retrieve them when answering questions.")
    .build()
    .prompt()
    .user("What was our Q2 launch deadline again?")
    .tools(tools)
    .call()
    .content();
```

Every tool returns a `Map<String, Object>` with `success: true` and
operation-specific fields on success, or `success: false` and an `error`
field on failure.

## Available tools

| Tool | Description |
|---|---|
| `goodmem_list_embedders` | List embedder models available on the server |
| `goodmem_list_spaces` | List all spaces accessible to the API key |
| `goodmem_get_space` | Fetch a space by UUID |
| `goodmem_create_space` | Create a space (idempotent by name) |
| `goodmem_update_space` | Update a space's name, labels, or public-read flag |
| `goodmem_delete_space` | Delete a space and all of its memories |
| `goodmem_create_memory` | Store text or a file as a memory |
| `goodmem_list_memories` | List memories in a space, with pagination and filters |
| `goodmem_retrieve_memories` | Semantic retrieval across one or more spaces |
| `goodmem_get_memory` | Fetch a memory by ID, with optional content |
| `goodmem_delete_memory` | Delete a memory |

### Retrieval options

`goodmem_retrieve_memories` accepts the following parameters in addition to
`query` and `spaceIds`:

| Parameter | Type | Description |
|---|---|---|
| `maxResults` | `Integer` | Maximum number of matching chunks (default 5) |
| `includeMemoryDefinition` | `Boolean` | Include full memory metadata alongside the matched chunks |
| `waitForIndexing` | `Boolean` | Poll for results when none come back on the first call |
| `rerankerId` | `String` | Reranker model to refine result ordering |
| `llmId` | `String` | LLM that generates a contextual `abstractReply` |
| `relevanceThreshold` | `Double` | Minimum score (0..1) for inclusion |
| `llmTemperature` | `Double` | Creativity (0..2) for the LLM post-processor |
| `chronologicalResort` | `Boolean` | Reorder results by creation time |

A `metadata_filter` parameter is available on the lower-level
`GoodMemClient.retrieveMemories(...)` Java API for narrowing results by a
SQL-style JSONPath expression applied server-side. Example:
`CAST(val('$.category') AS TEXT) = 'feat'`.

## Environment variables

| Variable | Description |
|---|---|
| `GOODMEM_BASE_URL` | Base URL of the GoodMem API server (default `https://localhost:8080`) |
| `GOODMEM_API_KEY` | API key sent as the `X-API-Key` header |
| `GOODMEM_VERIFY_SSL` | Set to `false` to skip TLS verification (default `true`) |

## End-to-end example

[`examples/README.md`](examples/README.md) walks through a runnable end-to-end
demo that drives three scenarios from a Spring AI `ChatClient`: persistent
project context, a scribe and analyst pipeline, and metadata-driven
retrieval. The answering step uses OpenAI; set `OPENAI_API_KEY` before
running.

## Direct (non-tool) usage

`GoodMemClient` is framework-agnostic and can be called directly when you do
not need a chat model in the loop:

```java
GoodMemClient client = GoodMemClient.builder()
    .baseUrl("https://localhost:8080")
    .apiKey("your-api-key")
    .verifySsl(false)
    .build();

var space = client.createSpace("knowledge-base", embedderId, "recursive", 512, 50);
var memory = client.createMemory(
    space.get("spaceId").toString(),
    "Spring AI is a framework for AI applications.",
    null,
    null);
var matches = client.retrieveMemories(
    "Java AI framework",
    space.get("spaceId").toString(),
    5, true, true, null, null, null, null, null);
```

## Compatibility

| Requirement | Notes |
|---|---|
| Java 17 or later | Built and tested on JDK 21 |
| Spring AI 1.0 or later | Required for the `@Tool` / `ToolParam` annotations |
| Jackson 2.x | Used for JSON request/response serialization |

## License

Apache License 2.0. See [LICENSE](LICENSE).
