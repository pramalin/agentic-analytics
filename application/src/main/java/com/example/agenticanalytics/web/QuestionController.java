package com.example.agenticanalytics.web;

import jakarta.validation.constraints.NotBlank;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final ChatClient chatClient;

    public QuestionController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public record QuestionRequest(@NotBlank String question, String conversationId) {}
    public record QuestionResponse(String question, String answer) {}
    public record ErrorResponse(String error, String detail) {}

    // ChatMemory only defines CONVERSATION_ID (the advisor param key) — there
    // is no built-in "default" constant, so this app defines its own.
    private static final String DEFAULT_CONVERSATION_ID = "default";

    @PostMapping
    public QuestionResponse ask(@RequestBody QuestionRequest request) {
        String conversationId = request.conversationId() == null
                ? DEFAULT_CONVERSATION_ID
                : request.conversationId();

        String answer = chatClient.prompt()
                .user(request.question())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        return new QuestionResponse(request.question(), answer);
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
