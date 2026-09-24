# Changelog

## 0.2.0

Audit release. Every defect below was reproduced against the `v0.1.0` tag
(`5448e57`) on a live GoodMem server (v1.0.320) before it was fixed — through the
connector's own compiled classes, not by reading the source.

### Fixed

- **A model-supplied `filePath` read any file on the machine.** `goodmem_create_memory`
  took a path from the model with no restriction; a probe read `/etc/hostname` and
  uploaded it. Text creation no longer takes a path; `GoodMemUploadTool` takes a
  directory the developer chooses and refuses anything that resolves outside it.
- **Reusing a space by name reported the embedder you asked for**, not the one the
  space uses, and wrote into it anyway. A live probe on a Voyage space asked for Qwen
  and was told `embedderId=Qwen, reused=true`. Reuse now requires a matching embedder;
  a mismatch is an error naming both.
- **Server-reported problems were discarded.** `status` events fell through the parser,
  and every result said `success: true` — a search whose reranker failed looked
  identical to one that worked. Statuses now reach the caller (`goodmem_partial`,
  `goodmem_statuses`); an unknown code surfaces as `UNKNOWN`; a truncated stream keeps
  what arrived and reports `MALFORMED_STREAM`.
- **An empty search polled for a minute.** `waitForIndexing` defaulted to `true` on the
  retrieve tool and was model-controllable; an empty space took 63.9s versus 0.33s
  without it. The read path never polls; `goodmem_create_memory` waits for its own
  memory instead.
- **The model was shown `arg0`…`arg9`.** The jar was compiled without `-parameters`,
  so Spring AI's tool schema named a ten-argument tool's properties `arg0` through
  `arg9`. The build now sets `<parameters>true</parameters>` and a test pins the names.
- **`publicRead` was a tool argument.** The server rejects it with HTTP 400
  ("Unrecognized field"). Removed.
- **Chunks and memories came back as two arrays** for the model to join by
  `memoryIndex`. Each result now carries its memory's metadata, joined by UUID.
- **`listSpaces` returned the first page only**, and `listMemories` handed the model a
  `nextToken` to manage. Listings follow pagination internally, bounded.
- **A memory's content took a second request**, and its failure became a
  `contentError` string under `success: true`. One request; a failure is a failure.
- **Scores were raw** (negative for vector search) and the threshold was documented
  "0..1". `Document.getScore()` is higher-is-better; reranker scores pass through with
  their kind recorded, since reranker ranges are provider-dependent.

### Changed

- Built on the official `ai.pairsys:goodmem-java` SDK (0.2.2) instead of a
  hand-written `HttpClient` wrapper. **The Java floor moves from 17 to 21**: the
  SDK is compiled for 21 and a JDK 17 build fails with "class file has wrong
  version 65.0" — found by CI, not locally, where only JDK 21 was installed.
- `GoodMemDocumentRetriever` implements Spring AI's `DocumentRetriever`, so GoodMem
  plugs into `RetrievalAugmentationAdvisor` — the connector had no native retrieval
  surface before.
- The eleven-tool `GoodMemTools` is split into `GoodMemSearchTool` (the model supplies
  only a query), `GoodMemAdminTools` (API-key authority, opt-in) and
  `GoodMemUploadTool` (opt-in, directory-confined). `relevanceThreshold`, `llmId`,
  `llmTemperature`, `chronologicalResort` and arbitrary `spaceIds` are no longer
  model-facing.
- Metadata filters via `GoodMemFilters`, with the escaping and casts the server
  accepts, verified live.
- `verifySsl(false)` is per connection and no longer appears in the quickstart.
- Tests: 28 offline (the real SDK over WireMock, event shapes captured live) and 9
  live, replacing 2 structural tests and 14 integration tests that passed against every
  defect above. CI added; the repository had a publish workflow but no test run.

### Removed

- `GoodMemClient`, `GoodMemTools`, `GoodMemClientException`. See the migration table
  in the README.
