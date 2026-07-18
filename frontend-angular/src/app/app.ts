import { Component, signal } from '@angular/core';
import { QuestionService, QuestionResponse, ToolCallTrace } from './question.service';

interface TraceViewModel extends ToolCallTrace {
  expanded: boolean;
  formattedArguments: string;
  formattedResult: string;
  widthPercent: number;
}

@Component({
  selector: 'app-root',
  imports: [],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  question = signal('How many total transactions are declined, across all time?');
  answer = signal<string | null>(null);
  traces = signal<TraceViewModel[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);

  constructor(private questionService: QuestionService) {}

  ask(question: string) {
    const trimmed = question.trim();
    if (!trimmed) return;

    this.loading.set(true);
    this.answer.set(null);
    this.traces.set([]);
    this.error.set(null);

    this.questionService.ask(trimmed, 'admin-console').subscribe({
      next: (res: QuestionResponse) => {
        this.loading.set(false);
        this.answer.set(res.answer);
        this.traces.set(this.buildTraceViewModels(res.traces));
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(err.error?.detail ?? err.message ?? 'Unknown error');
      },
    });
  }

  toggleExpanded(index: number) {
    this.traces.update((traces) =>
      traces.map((t, i) => (i === index ? { ...t, expanded: !t.expanded } : t))
    );
  }

  private buildTraceViewModels(raw: ToolCallTrace[]): TraceViewModel[] {
    const maxDuration = Math.max(...raw.map((t) => t.durationMs), 1);
    return raw.map((t) => ({
      ...t,
      expanded: false,
      formattedArguments: this.formatJson(t.arguments),
      formattedResult: this.formatToolResult(t.result),
      // Floor at 4% so even a near-instant call still shows a visible sliver —
      // otherwise the fastest call in a batch would render as an invisible
      // zero-width bar, which reads as "missing data," not "fast."
      widthPercent: Math.max((t.durationMs / maxDuration) * 100, 4),
    }));
  }

  private formatJson(raw: string): string {
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch {
      return raw;
    }
  }

  /**
   * This MCP server's tool results come back as a JSON-encoded array of
   * {text: "..."} objects — that's this specific server's own convention,
   * not something the MCP protocol or Spring AI requires. Extract the
   * readable text rather than showing escaped JSON with literal \n
   * characters, which is what you'd get from just pretty-printing it.
   */
  private formatToolResult(raw: string): string {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.every((item) => typeof item?.text === 'string')) {
        return parsed.map((item) => item.text).join('\n');
      }
      return JSON.stringify(parsed, null, 2);
    } catch {
      return raw;
    }
  }
}
