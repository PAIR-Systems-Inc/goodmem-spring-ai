# Examples

The live integration test is the worked example: `src/test/java/.../GoodMemLiveIT.java`
creates a space, writes memories, searches through the `DocumentRetriever` and the
search tool, asks a GoodMem LLM for an answer (and shows a missing one reported, not
hidden), filters with an apostrophe and an injection payload, uploads a file from a
confined directory, and deletes everything it made.

Run it against your server:

```bash
GOODMEM_BASE_URL=https://localhost:8080 \
GOODMEM_API_KEY=gm_... \
GOODMEM_EMBEDDER_ID=your-embedder-uuid \
GOODMEM_LLM_ID=your-llm-uuid \
GOODMEM_VERIFY_SSL=false \
  ./mvnw -B verify
```

For a chat-model example, wire `GoodMemDocumentRetriever` into
`RetrievalAugmentationAdvisor` as shown in the README quickstart.
