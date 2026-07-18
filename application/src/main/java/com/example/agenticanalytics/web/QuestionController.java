package com.example.agenticanalytics.web;

import com.example.agenticanalytics.tracing.ToolCallTrace;
import com.example.agenticanalytics.tracing.ToolCallTraceCollector;
import jakarta.validation.constraints.NotBlank;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/questions")
// React dev server (Step 7) and Angular dev server (Step 9, planned) run on
// different origins than the API in local dev — the browser calls this
// directly, so it needs explicit CORS allowance. 5173 is Vite's actual
// default dev port (verified against a real vite.config.ts, not assumed);
// 3000 is where the Docker-built/nginx-served frontend ends up; 4200 is
// reserved for Angular. Dev-server ports only — production would serve the
// frontend from behind the same origin/proxy as the API, which wouldn't
// need this at all.
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000", "http://localhost:4200"})
public class QuestionController {

    private final ChatClient chatClient;
    private final ToolCallTraceCollector traceCollector;

    public QuestionController(ChatClient chatClient, ToolCallTraceCollector traceCollector) {
        this.chatClient = chatClient;
        this.traceCollector = traceCollector;
    }

    public record QuestionRequest(@NotBlank String question, String conversationId) {}
    public record QuestionResponse(String question, String answer, List<ToolCallTrace> traces) {}
    public record ErrorResponse(String error, String detail) {}

    // ChatMemory only defines CONVERSATION_ID (the advisor param key) — there
    // is no built-in "default" constant, so this app defines its own.
    private static final String DEFAULT_CONVERSATION_ID = "default";

    @PostMapping
    public QuestionResponse ask(@RequestBody QuestionRequest request) {
        String conversationId = request.conversationId() == null
                ? DEFAULT_CONVERSATION_ID
                : request.conversationId();

        // Step 8: trace capture, for the Angular admin console (Step 9).
        // start()/drainAndClear() must be paired on every path, including
        // the exception one — see ToolCallTraceCollector's javadoc for why
        // a leaked ThreadLocal entry would be a real bug on Tomcat's
        // pooled threads, not just untidy.
        traceCollector.start();
        try {
            String answer = chatClient.prompt()
                    .user(request.question())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            return new QuestionResponse(request.question(), answer, traceCollector.drainAndClear());
        } catch (RuntimeException ex) {
            traceCollector.drainAndClear();
            throw ex;
        }
    }

    /**
     * Everything the model provider client can throw (auth failures, rate
     * limits, network errors, quota exceeded) lands here rather than as a
     * bare framework 500. Deliberately not distinguishing exception types
     * yet — with three provider paths (Anthropic, OpenAI, Ollama) each
     * throwing their own provider-specific exception classes, a single
     * catch-all that surfaces the real message is more useful right now
     * than partial per-provider handling that misses whichever path isn't
     * covered.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleChatError(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(
                        "The model provider request failed.",
                        ex.getMessage()));
    }
}
