package com.example.agenticanalytics.tracing;

/**
 * One recorded tool invocation — what was called, with what arguments,
 * what came back, and how long it took. Serialized directly into
 * QuestionResponse for the admin console (Step 9) to render.
 */
public record ToolCallTrace(
        String toolName,
        String arguments,
        String result,
        long durationMs
) {}
