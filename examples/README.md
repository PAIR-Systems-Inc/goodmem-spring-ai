# Spring AI GoodMem example

End-to-end demo that drives [`GoodMemTools`](../src/main/java/ai/goodmem/springai/GoodMemTools.java)
from a Spring AI `ChatClient`. Three scenarios cover the integration's common
usage patterns:

1. **Persistent project context across sessions.** A single agent stores
   project facts the user shares and then answers a question after rotating
   the chat history. The conversation memory is cleared between phases, so
   the agent has to call `goodmem_retrieve_memories` to recover the answer.
2. **Two-agent team knowledge pipeline.** A Scribe agent ingests team notes
   into a shared space; an Analyst agent answers questions from the same
   space. The two agents share a memory store but not chat history.
3. **Structured activity log with metadata filtering.** A Tagger agent
   stores entries with a `category` metadata field. A Release manager agent
   queries the space with `metadataFilter` set to a SQL/JSONPath expression
   so the LLM only sees the category being asked about.

The example lives in
[`GoodMemExampleIT`](../src/test/java/ai/goodmem/springai/GoodMemExampleIT.java).

## Prerequisites

- A reachable GoodMem server with at least one configured embedder.
- An OpenAI API key. The demo answers with `gpt-4o-mini` by default.
- Java 17 or later.

## Environment variables

| Variable | Description |
|---|---|
| `GOODMEM_BASE_URL` | Base URL of the GoodMem API server (default `https://localhost:8080`) |
| `GOODMEM_API_KEY` | API key sent as the `X-API-Key` header |
| `GOODMEM_VERIFY_SSL` | Set to `false` to skip TLS verification (default `true`) |
| `OPENAI_API_KEY` | OpenAI key the agent uses for the answering step |

The example is skipped if `GOODMEM_API_KEY` or `OPENAI_API_KEY` is missing.

## Run it

```bash
GOODMEM_BASE_URL=https://localhost:8080 \
GOODMEM_API_KEY=... \
GOODMEM_VERIFY_SSL=false \
OPENAI_API_KEY=... \
./mvnw -Dit.test=GoodMemExampleIT verify
```

Each scenario prints the user turn and the agent reply. The agent's calls
to `goodmem_create_memory`, `goodmem_retrieve_memories`, and the rest of the
tool surface happen automatically as part of the Spring AI tool-calling
loop.

## What to look for

- **Scenario 1**: the agent answers the coverage question from the memory
  store after the chat history has been rotated, proving the facts came
  from GoodMem and not from the LLM's conversational context.
- **Scenario 2**: the Analyst answers a synthesis question that requires
  pulling several stored notes back into context at once.
- **Scenario 3**: the Release manager reply contains only the `feat`
  entries, confirming the `metadataFilter` ran server-side and the LLM
  never saw the `fix`, `chore`, or `docs` rows.
