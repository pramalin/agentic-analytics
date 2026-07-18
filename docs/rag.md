# RAG (Schema Documentation Retrieval)

> **Status: currently disabled.** `ChatClientConfig` has the
> `RetrievalAugmentationAdvisor` commented out of the advisor chain. It
> broke multi-turn tool-calling — see "The multi-turn regression" below.
> Until that's fixed, the agent relies solely on the Step 5j system-prompt
> instruction (`SELECT DISTINCT` before filtering on an undocumented text
> column) as its only defense against a repeat of the status-casing bug
> this feature was originally built to prevent. That's a real, known gap,
> not an oversight — see "Where things stand" at the end of this doc.

As originally built in Step 6, the agent's system prompt was meant to be
augmented with retrieved excerpts from `schema-docs/*.md` — plain-English
documentation of what each table and column means, including value
conventions that `describe_table` structurally cannot express (column
names and types only, never the actual values stored).

## Why this exists — directly, not abstractly

Step 5i and 5j found a real bug: the agent generated `status = 'declined'`
against a column that actually stores `'DECLINED'` (uppercase). Postgres
string comparison is case-sensitive, so the query silently matched zero
rows and the agent reported "no declined transactions" — a wrong answer
that looked like a legitimate empty result.

The interim fix (Step 5i/5j) was prompt engineering: instructing the model
to run `SELECT DISTINCT` before filtering on any text column. That worked,
*sometimes* — Step 5j reproduced the exact same bug on a re-run with an
unchanged, verified-correct prompt, because GPT-4o isn't run at temperature
0 and an advisory instruction is a nudge, not a guarantee.

RAG was meant to close this gap differently: instead of asking the model
to remember to check, the correct fact (`transaction.status` values are
uppercase) would be retrieved and placed directly in the model's context
for every relevant question, every time, deterministically — retrieval
doesn't depend on the model's discretion the way an instruction buried in
a long system prompt does. That's still the right idea; the *implementation*
had a bug serious enough to disable it (see below).

## How it works, when enabled

- `SchemaDocIngestor` (an `ApplicationRunner`) embeds every `.md` file
  under `schema-docs/` into pgvector on startup.
- `ChatClientConfig` wires a `RetrievalAugmentationAdvisor` (backed by
  `VectorStoreDocumentRetriever`, top-5) ahead of the memory advisor in
  the advisor chain — retrieved schema facts get injected before the
  model call happens, on every request.
- The system prompt tells the model to treat retrieved documentation as
  authoritative fact, not something to re-verify — while keeping the
  Step 5j `SELECT DISTINCT` instruction as a second layer for anything
  the docs don't happen to cover.

**Embedding model note, a separate fix from an earlier point in this
project:** this originally ran on a local ONNX model
(`spring-ai-starter-model-transformers`, `all-MiniLM-L6-v2`) to avoid any
API cost or dependency on which chat provider was active. That hit a
real, previously-reported upstream bug
([spring-projects/spring-ai#1391](https://github.com/spring-projects/spring-ai/issues/1391)):
Spring AI's default autoconfiguration fetches the model files from
`raw.githubusercontent.com`, which serves a broken Git-LFS pointer stub
(133 bytes) instead of the real binary, and the app hung trying to use it.
Switched to OpenAI's `text-embedding-3-small` instead — reliable, but now
couples RAG to the OpenAI overlay specifically (`compose.anthropic.yaml`,
`compose.ollama.yaml`, and `compose.docker-model-runner.yaml` don't have a
working embeddings path right now). A real follow-up item, tracked here
rather than silently left as a surprise.

## The multi-turn regression — why this is disabled

Found through direct testing, the same way every other real bug in this
project got found: the agent worked correctly on the first question in a
conversation, every time, then answered "I don't know" — instantly, with
no tool calls at all — on every question after that, every time. 100%
reproducible, regardless of which two questions were asked.

Application DEBUG logs (`org.springframework.ai: DEBUG`) made the
mechanism visible directly rather than requiring guesswork:

- **Turn 1**: RAG augmenter fires → `list_tables` → RAG fires again →
  `describe_table` → RAG fires again → `execute_sql` → RAG fires once
  more for final answer generation. Real tool calls, correct answer,
  ~17s.
- **Turn 2**: RAG augmenter fires **once** — then nothing. No
  `Executing tool call` line at all. The model returned "I don't know"
  without ever asking to call a tool.

This ruled out the two theories tried first:
- **Not a memory-window truncation issue** — raising
  `MessageWindowChatMemory` from 20 to 100 messages had no effect; the
  failure happened on the very first follow-up question regardless.
- **Not a dropped-tools bug** — DEBUG logs confirmed the RAG advisor
  engaged normally on turn 2, same as turn 1's first pass; tools weren't
  withheld, the model simply never asked for one.

**Isolating test**: commented out `ragAdvisor` in `ChatClientConfig`'s
`defaultAdvisors(...)` call, rebuilt, ran four consecutive real questions
in one conversation. All four worked correctly, with real tool calls and
correct answers, every time. That confirms the RAG advisor itself as the
cause, not conversation memory or the tool-calling chain independently.

**Best current hypothesis, not yet root-caused further:** on turn 2+,
the model's context contains both (a) turn 1's full tool-call transcript
(tool-call requests and their results, which `MessageChatMemoryAdvisor`
keeps in memory) and (b) freshly retrieved schema *documentation* for the
new question. It's plausible GPT-4o reads that combination as "this
question is already answered" — even though what's retrieved is
documentation about table structure, not query results — and skips
tool-calling entirely as a result. This hasn't been proven further than
the A/B test above; it's the most likely explanation given the evidence,
not a confirmed mechanism.

## Where things stand

- RAG is disabled in the live advisor chain. The status-casing bug this
  feature exists to prevent is currently only guarded by the Step 5j
  system-prompt instruction, which is known to be unreliable under
  GPT-4o's non-zero temperature (see Step 5j's own history for a
  reproduction of that unreliability).
- **A second, independent reason this isn't ready to casually re-enable,**
  found while wiring up Step 8's tool-call tracing: `SchemaDocIngestor`
  ran unconditionally on every startup — including in `mvn test`'s
  full-context tests — even though nothing has read its embeddings since
  the advisor was disabled. Once the embedding model moved from local
  ONNX to a real OpenAI API call (see the note above on spring-ai#1391),
  this meant every startup made a real, billed OpenAI embedding call for
  a feature nothing uses, and a placeholder test key surfaced as a real
  `401 Unauthorized` failing `AgenticAnalyticsApplicationTests`. Fixed by
  gating `SchemaDocIngestor` behind `app.rag.enabled` (default `false`,
  matching RAG's actual disabled status) — re-enabling RAG now means
  flipping that property too, not just uncommenting the advisor.
- `SchemaDocIngestor` and the vector store still work correctly and are
  still tested (`SchemaDocIngestorIT`) — the ingestion and retrieval
  machinery itself isn't broken, only using it live in the chat advisor
  chain is. That test now requires a real `OPENAI_API_KEY` to mean
  anything (a placeholder key can't produce a meaningful embedding, only
  a 401), so it's gated with `@EnabledIfEnvironmentVariable` — it skips
  itself gracefully rather than failing when no real key is present,
  keeping this project's "tests never need a real key" convention intact
  everywhere else.
- Real next steps, not done yet:
  1. Scope RAG retrieval to only the first turn of a conversation (e.g.
     conditionally skip the advisor once memory already has prior turns),
     rather than re-augmenting every turn.
  2. Restructure `MessageChatMemoryAdvisor` to retain only final Q&A pairs
     across turns, not full intermediate tool-call transcripts — might
     independently resolve this and reduce token usage regardless of RAG.
  3. Re-run the same multi-turn test after either change, the same way
     this bug was found — empirically, not by assumption.

## Testing

`SchemaDocIngestorIT` confirms the ingestion apparatus actually runs and
that a query resembling "what casing are status values stored in"
retrieves the `transaction.md` chunk that documents it — this test still
passes and still verifies real functionality; it just isn't exercised by
the live chat path right now.
