import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { useState, useRef, useEffect, type FormEvent } from "react";
import { askQuestion, ApiError } from "./api";
import "./App.css";

interface Turn {
  id: string;
  question: string;
  askedAt: Date;
  status: "pending" | "done" | "error";
  answer?: string;
  errorDetail?: string;
  latencyMs?: number;
}

const EXAMPLE_QUESTION_POOL = [
  "Declined transactions by region, last quarter",
  "Total transactions declined, across all time",
  "How many merchants are in the Northeast region?",
  "What are the most common decline reasons?",
  "Approved vs declined transaction counts, all time",
  "Which region has the highest decline rate?",
  "How many merchants do we have per region?",
  "Total transaction volume in dollars, this month",
];

function pickRandomQuestions(exclude: string[], count: number): string[] {
  const pool = EXAMPLE_QUESTION_POOL.filter((q) => !exclude.includes(q));
  const shuffled = [...pool].sort(() => Math.random() - 0.5);
  return shuffled.slice(0, count);
}

function newId(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2);
}

export default function App() {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState("");
  const [suggestions, setSuggestions] = useState(() => pickRandomQuestions([], 3));
  const conversationId = useRef(newId());
  const transcriptEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    transcriptEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [turns]);

  async function submitQuestion(question: string) {
    const trimmed = question.trim();
    if (!trimmed) return;

    const id = newId();
    const startedAt = performance.now();
    setTurns((prev) => [
      ...prev,
      { id, question: trimmed, askedAt: new Date(), status: "pending" },
    ]);
    setInput("");

    try {
      const result = await askQuestion(trimmed, conversationId.current);
      const latencyMs = performance.now() - startedAt;
      setTurns((prev) =>
        prev.map((t) =>
          t.id === id ? { ...t, status: "done", answer: result.answer, latencyMs } : t
        )
      );
      setSuggestions((prev) => pickRandomQuestions([...prev, trimmed], 3));
    } catch (err) {
      const detail = err instanceof ApiError ? err.detail : "Unable to reach the data mart assistant.";
      setTurns((prev) =>
        prev.map((t) => (t.id === id ? { ...t, status: "error", errorDetail: detail } : t))
      );
    }
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    submitQuestion(input);
  }

  return (
    <div className="page">
      <div className="sheet">
        <header className="masthead">
          <p className="eyebrow">Enterprise Data Intelligence</p>

          <h1>AI Data Mart Assistant</h1>

          <p className="subhead">
            Ask business questions in plain English and receive answers grounded in
            verified, read-only enterprise data.
          </p>
        </header>

        <main className="transcript" aria-live="polite">
          {turns.length === 0 && (
            <div className="empty-state">
              <p>No questions yet. Try one of these, or write your own below.</p>
            </div>
          )}

          {turns.map((turn, index) => (
            <article key={turn.id} className="entry" data-status={turn.status}>
              <div className="entry-question">
                <span className="entry-number">Q{index + 1}</span>
                <span className="entry-question-text">{turn.question}</span>
                <time className="entry-time">
                  {turn.askedAt.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                </time>
              </div>

              <div className="entry-rule" aria-hidden="true" />

              {turn.status === "pending" && (
                <p className="entry-pending">Querying the data mart&hellip;</p>
              )}

              {turn.status === "done" && (
                <div className="entry-answer">
                  <div className="markdown-answer">
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{turn.answer}</ReactMarkdown>
                  </div>
                  <span className="stamp">
                    Q{index + 1} &middot; verified &middot;{" "}
                    {turn.latencyMs ? `${(turn.latencyMs / 1000).toFixed(1)}s` : ""}
                  </span>
                </div>
              )}

              {turn.status === "error" && (
                <div className="entry-error">
                  <p>Query failed &mdash; see detail below.</p>
                  <code>{turn.errorDetail}</code>
                </div>
              )}
            </article>
          ))}
          <div ref={transcriptEndRef} />
        </main>

        <div className="suggestions-bar">
          <span className="suggestions-label">Try:</span>
          {suggestions.map((q) => (
            <button
              key={q}
              type="button"
              className="example-chip"
              onClick={() => submitQuestion(q)}
            >
              {q}
            </button>
          ))}
        </div>          

        <form className="composer" onSubmit={handleSubmit}>
          <label htmlFor="question-input" className="visually-hidden">
            Ask a question about the data mart
          </label>
          <input
            id="question-input"
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Ask about transactions, declines, regions&hellip;"
            autoComplete="off"
          />
          <button type="submit" disabled={!input.trim()}>
            Ask &rarr;
          </button>
        </form>
      </div>
    </div>
  );
}