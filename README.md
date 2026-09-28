# goodmem-spring-ai

A [GoodMem](https://goodmem.ai) connector for [Spring AI](https://spring.io/projects/spring-ai).

> **Status — 0.2.1 (unreleased).** Built on the official `ai.pairsys:goodmem-java` SDK.
> 196 offline tests drive the real SDK over a mock server; 9 live tests run against a
> GoodMem server. 0.2.0 is on Maven Central; 0.2.1 is not released yet, so install it
> from source (below).

GoodMem gives agents retrieval-augmented memory: text goes in, GoodMem chunks and
embeds it server-side, and semantic search brings the relevant passages back. This
connector plugs that into Spring AI three ways:

- **`GoodMemDocumentRetriever`** — a Spring AI `DocumentRetriever`, for
  `RetrievalAugmentationAdvisor` and any RAG pipeline that takes one.
- **`GoodMemSearchTool`** — one `@Tool` an agent can call; the model supplies a query.
- **`GoodMemAdminTools`** and **`GoodMemUploadTool`** — management and file upload, for
  agents that are meant to administer GoodMem.

## Installation

0.2.1 is not on Maven Central yet. 0.2.0 is, but it lacks the id check described
under [Administration tools](#administration-tools). Build and install 0.2.1 locally:

```bash
./mvnw -B install -DskipTests
```

```xml
<dependency>
    <groupId>io.github.bashareid</groupId>
    <artifactId>goodmem-spring-ai</artifactId>
    <version>0.2.1</version>
</dependency>
```

Requires **Java 21+** (the GoodMem Java SDK is compiled for 21), Spring AI 1.0.0
(`spring-ai-model` and `spring-ai-rag` are `provided`; your application already has
them) and a GoodMem server.

## Quickstart: RAG with a DocumentRetriever

```java
import ai.pairsys.goodmem.springai.GoodMemConnection;
import ai.pairsys.goodmem.springai.GoodMemDocumentRetriever;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

GoodMemConnection connection = GoodMemConnection.builder()
    .baseUrl("https://goodmem.example.com:8080")
    .apiKey(System.getenv("GOODMEM_API_KEY"))
    .build();

GoodMemDocumentRetriever retriever = GoodMemDocumentRetriever.builder()
    .connection(connection)
    .spaceId("your-space-uuid")   // the developer decides what is searched
    .topK(5)
    .build();

String answer = ChatClient.builder(chatModel).build()
    .prompt()
    .advisors(RetrievalAugmentationAdvisor.builder().documentRetriever(retriever).build())
    .user("When does payroll run?")
    .call()
    .content();
```

Each returned `Document` has the chunk text, the memory's metadata, and:

| metadata key | meaning |
|---|---|
| `goodmem_partial` | `true` when the server reported a problem with this search |
| `goodmem_statuses` | the statuses it reported, when any |
| `goodmem_memory_id`, `goodmem_chunk_id`, `goodmem_space_id` | where the text came from |
| `goodmem_score_kind` | `vector` or `reranker` |
| `goodmem_raw_score` | the server's value, before any adjustment |
| `source` | the memory's `originalContentRef` when it has one, else its id — for citations |

`Document.getScore()` is higher-is-better. A GoodMem vector score is a negative inner
product (the best match is the lowest number), so it is negated. A reranker score is
passed through unchanged; its range is provider-dependent (Voyage rerank-2.5 returns
roughly `0.27..0.93`, Jina v3 `-0.14..0.43`), so do not assume 0–1 when choosing a
threshold.

**Incomplete results are reported, never hidden.** If the server reports a problem
(a reranker that failed, a space that could not be searched, a code this SDK does not
know yet), the results still come back with `goodmem_partial=true` and the statuses.
A search that reported a problem and found nothing returns an empty list and logs a
warning; it does not throw.

## Give an agent a search tool

```java
GoodMemSearchTool search = new GoodMemSearchTool(retriever);

String reply = ChatClient.builder(chatModel).build()
    .prompt()
    .tools(search)
    .user("What do we know about the Q3 offsite?")
    .call()
    .content();
```

The tool is called **`goodmem_search`**; the model supplies only `query` and,
optionally, `topK`. Spaces, reranker and filter stay with the developer. The tool never fails on a server-reported status; it returns
`partial` and `statuses` alongside `results`.

### Reranking

```java
GoodMemDocumentRetriever.builder()
    .connection(connection)
    .spaceId("...")
    .rerankerId("your-reranker-uuid")
    .build();
```

### Metadata filters

Filters are GoodMem expressions evaluated server-side. Build them with
`GoodMemFilters` rather than by string interpolation — it uses the escaping and casts
the server actually accepts, verified live: a value with an apostrophe is a value, not
syntax; numbers use `NUMERIC`; booleans use `BOOLEAN` (a boolean compared as text is
accepted by the server and matches nothing).

```java
String filter = GoodMemFilters.allOf(
    GoodMemFilters.textEquals("team", "finance"),
    GoodMemFilters.compare("year", ">=", 2026));

GoodMemDocumentRetriever.builder().connection(connection).spaceId("...").filter(filter).build();
```

## Administration tools

`GoodMemAdminTools` carries the API key's full authority — a model holding it can
delete spaces — so give it only to agents that administer GoodMem:

| tool | what it does |
|---|---|
| `goodmem_create_space` | creates a space, or reuses one of the same name **only if its embedder matches** |
| `goodmem_list_spaces`, `goodmem_get_space`, `goodmem_delete_space` | |
| `goodmem_create_memory` | stores text and **waits for indexing**, so a search right after finds it |
| `goodmem_list_memories`, `goodmem_get_memory`, `goodmem_delete_memory` | listings follow pagination internally |
| `goodmem_list_embedders` | |

Every id argument (`spaceId`, `memoryId`, `embedderId`) must be a UUID. Anything else
is refused with `success=false` before any request is made, because the SDK puts ids
into URL paths and resolves `..` in them: in 0.2.0, `goodmem_delete_memory` given
`../spaces/<uuid>` sent `DELETE /v1/spaces/<uuid>` and reported success. The same check
applies to the retriever's `spaceId` and `rerankerId`, where `build()` throws
`IllegalArgumentException`.

```java
GoodMemAdminTools admin = new GoodMemAdminTools(connection);           // waits up to 60s for indexing
GoodMemAdminTools quick = new GoodMemAdminTools(connection, false, Duration.ZERO, 200);
```

### Uploading files

The model never names a path. `GoodMemUploadTool` takes a directory you choose (it
must already exist; the constructor throws `IllegalArgumentException` otherwise) and a
file name relative to it, and refuses anything — including a symlink — that resolves
outside that directory.

```java
GoodMemUploadTool upload = new GoodMemUploadTool(connection, Path.of("/srv/agent-uploads"));
```

The tool is called **`goodmem_upload_file`** and takes `spaceId` (a UUID), `fileName`
and optional `metadata`.

## Connection settings

| setting | default | notes |
|---|---|---|
| `baseUrl` | — | required |
| `apiKey` | — | required; sent as the `x-api-key` header, never in a URL or log |
| `timeout` | 30s | per request |
| `verifySsl` | `true` | Setting it to `false` disables certificate checks for **this connection only**, for a local server with a self-signed certificate. Not for production, and deliberately absent from the quickstart. |

You can also hand in an SDK client you configured yourself with
`GoodMemConnection.of(goodmemClient)`; it stays yours and is never closed here.

## Migrating from 0.1.0

0.1.0's `GoodMemClient` and `GoodMemTools` are gone. Every change below was
reproduced live before it was made; see `CHANGELOG.md`.

| 0.1.0 | 0.2.0 |
|---|---|
| `GoodMemClient.builder()…` | `GoodMemConnection.builder()…` |
| `new GoodMemTools(client)` — eleven tools in one object | `new GoodMemSearchTool(retriever)` for reading; `GoodMemAdminTools` and `GoodMemUploadTool` opt-in |
| `goodmem_retrieve_memories(query, spaceIds, maxResults, includeMemoryDefinition, waitForIndexing, rerankerId, llmId, relevanceThreshold, llmTemperature, chronologicalResort)` | `goodmem_search(query, topK)` — everything else is configured on the retriever |
| `goodmem_create_memory(spaceId, textContent, filePath, …)` | `goodmem_create_memory(spaceId, text, metadata)`; files via `GoodMemUploadTool` |
| `goodmem_update_space(…, publicRead, …)` | removed — the server rejects `publicRead` with 400; labels can be edited through the SDK |
| results as `results[]` + `memories[]` joined by `memoryIndex` | one entry per chunk with its memory's metadata attached |
| `relevanceScore` raw (negative for vector search) | `score` higher-is-better; raw value kept |

## Development

These are the commands CI runs.

```bash
./mvnw -B verify                       # JDK 21+; 196 offline tests, the 9 live ones skip without credentials

GOODMEM_BASE_URL=https://localhost:8080 \
GOODMEM_API_KEY=gm_... \
GOODMEM_EMBEDDER_ID=your-embedder-uuid \
GOODMEM_VERIFY_SSL=false \
  ./mvnw -B verify                     # also runs the live tests; they delete what they create
```

CI runs the same `verify` on JDK 21, plus two gates: no committed GoodMem API key
(`gm_` followed by 20 or more lowercase letters or digits, anywhere in a line), and no call disabling TLS
verification in this README or in `examples/` — the quickstart must not teach it.

The offline tests drive the real SDK over a WireMock server, with event shapes captured
from a live GoodMem v1.0.320 (`src/test/resources/retrieve_real.ndjson`). Nothing in the
connector or the SDK is stubbed. `GoodMemIdPathTraversalTests` sends every id-taking
entry point fourteen traversal and malformed ids (`../spaces/<uuid>`, `%2e%2e/…`,
`<uuid>?x=1`, …) against a plain JDK HTTP server that records each request line as it
arrived, and asserts that nothing reaches it.

## License

Apache License 2.0.
