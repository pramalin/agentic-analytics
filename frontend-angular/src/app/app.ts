import { Component, signal } from '@angular/core';
import { QuestionService, QuestionResponse } from './question.service';
import { JsonPipe } from '@angular/common';

@Component({
  selector: 'app-root',
  imports: [JsonPipe],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  result = signal<QuestionResponse | null>(null);
  error = signal<string | null>(null);

  constructor(private questionService: QuestionService) {}

  ask(question: string) {
    this.result.set(null);
    this.error.set(null);
    this.questionService.ask(question, 'admin-console-dev-test').subscribe({
      next: (res) => this.result.set(res),
      error: (err) => this.error.set(err.error?.detail ?? err.message ?? 'Unknown error'),
    });
  }
}