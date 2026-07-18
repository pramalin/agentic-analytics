import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ToolCallTrace {
  toolName: string;
  arguments: string;
  result: string;
  durationMs: number;
}

export interface QuestionResponse {
  question: string;
  answer: string;
  traces: ToolCallTrace[];
}

// Same reasoning as the React app's api.ts: this always needs to be a
// browser-reachable address, never a Docker-network one — the browser
// tab doesn't participate in Docker's internal networking regardless of
// how this app itself is served.
const API_BASE_URL = 'http://localhost:8080';

@Injectable({ providedIn: 'root' })
export class QuestionService {
  constructor(private http: HttpClient) {}

  ask(question: string, conversationId: string): Observable<QuestionResponse> {
    return this.http.post<QuestionResponse>(`${API_BASE_URL}/api/questions`, {
      question,
      conversationId,
    });
  }
}