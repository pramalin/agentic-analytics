// Base URL is always resolved from the browser, never from the Docker
// network — a browser tab doesn't participate in Docker's internal
// networking, so this always needs to be a host-reachable address, whether
// running via `npm run dev` or a Docker-built preview server.
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export interface QuestionResponse {
  question: string;
  answer: string;
}

export interface ApiErrorResponse {
  error: string;
  detail: string;
}

export class ApiError extends Error {
  readonly detail: string;

  constructor(detail: string) {
    super(detail);
    this.detail = detail;
  }
}

export async function askQuestion(
  question: string,
  conversationId: string
): Promise<QuestionResponse> {
  const response = await fetch(`${API_BASE_URL}/api/questions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question, conversationId }),
  });

  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as ApiErrorResponse | null;
    throw new ApiError(body?.detail ?? `Request failed (${response.status})`);
  }

  return response.json();
}