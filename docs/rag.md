# RAG (Schema Documentation Retrieval)

As of Step 6, the agent's system prompt is augmented with retrieved excerpts
from `schema-docs/*.md` — plain-English documentation of what each table and
column means, including value conventions that `describe_table` structurally
cannot express (column names and types only, never the actual values stored).

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

RAG closes this gap differently: instead of asking the model to remember to
check, the correct fact (`transaction.status` values are uppercase) is
retrieved and placed directly in the model's context for every relevant
question, every time, deterministically — retrieval doesn't depend on the
model's discretion the way an instruction buried in a long system prompt
does.

## How it works

- `SchemaDocIngestor` (an `ApplicationRunner`) embeds every `.md` file under
  `schema-docs/` into pgvector on startup, using a local ONNX embedding
  model (`all-MiniLM-L6-v2`, bundled in the `spring-ai-starter-model-transformers`
  jar — no download, no API cost, no dependency on which chat provider is
  active).
- `ChatClientConfig` wires a `RetrievalAugmentationAdvisor` (backed by
  `VectorStoreDocumentRetriever`, top-5) ahead of the memory advisor in the
  advisor chain — retrieved schema facts get injected before the model call
  happens, on every request.
- The system prompt tells the model to treat retrieved documentation as
  authoritative fact, not something to re-verify — while keeping the Step
  5j `SELECT DISTINCT` instruction as a second layer for anything the docs
  don't happen to cover.

## What this doesn't solve

RAG doesn't replace `SELECT DISTINCT` for columns nobody's documented yet —
it only covers what's actually written in `schema-docs/`. It's also not
immune to retrieval missing the relevant chunk on a given query (`topK=5`
is a judgment call, not a guarantee every relevant fact gets retrieved
every time). This is genuinely more reliable than prompt-only guidance,
not perfectly reliable.

## Testing

`SchemaDocIngestorIT` confirms the ingestion actually runs and that a
query resembling "what casing are status values stored in" retrieves the
`transaction.md` chunk that documents it — not just that the app starts
without crashing.
