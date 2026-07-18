package com.example.agenticanalytics.tracing;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a real {@link ToolCallback} (in this app, always one sourced from
 * the MCP gateway — see ChatClientConfig) to record what was called, with
 * what arguments, what came back, and how long it took. Every call is
 * delegated unchanged; this only observes. Spring AI's tool-calling
 * machinery has no idea this wrapper exists.
 */
public class TracingToolCallback implements ToolCallback {

    private static final int MAX_RECORDED_LENGTH = 2000;

    private final ToolCallback delegate;
    private final ToolCallTraceCollector collector;

    public TracingToolCallback(ToolCallback delegate, ToolCallTraceCollector collector) {
        this.delegate = delegate;
        this.collector = collector;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        long startMillis = System.currentTimeMillis();
        String result = delegate.call(toolInput);
        recordTrace(toolInput, result, startMillis);
        return result;
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long startMillis = System.currentTimeMillis();
        String result = delegate.call(toolInput, toolContext);
        recordTrace(toolInput, result, startMillis);
        return result;
    }

    private void recordTrace(String toolInput, String result, long startMillis) {
        long durationMs = System.currentTimeMillis() - startMillis;
        collector.record(new ToolCallTrace(
                delegate.getToolDefinition().name(),
                truncate(toolInput),
                truncate(result),
                durationMs
        ));
    }

    /** Caps trace payload size — execute_sql can return up to 200 rows;
     *  no reason to ship all of that back through the trace field too. */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MAX_RECORDED_LENGTH
                ? value.substring(0, MAX_RECORDED_LENGTH) + "... (truncated)"
                : value;
    }
}
