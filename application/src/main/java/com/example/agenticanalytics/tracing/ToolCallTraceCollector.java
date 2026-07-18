package com.example.agenticanalytics.tracing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects tool-call traces for a single HTTP request using a
 * {@link ThreadLocal} rather than Spring's {@code @RequestScope}.
 *
 * Why ThreadLocal works here: the MCP client is configured as SYNC
 * (spring.ai.mcp.client.type: SYNC in application.yml), so tool invocation
 * blocks the calling thread rather than handing off to a separate reactor
 * thread. That means {@link TracingToolCallback#call} always executes on
 * the same thread that's handling the HTTP request, so a plain ThreadLocal
 * is visible where it needs to be — no request-context propagation
 * machinery required.
 *
 * IMPORTANT: {@link #start()} and {@link #drainAndClear()} must always be
 * paired, including on the exception path (see QuestionController) —
 * Tomcat reuses threads across requests, so a ThreadLocal that's never
 * cleared leaks stale trace data into whichever unrelated request happens
 * to reuse that thread next.
 */
@Component
public class ToolCallTraceCollector {

    private final ThreadLocal<List<ToolCallTrace>> traces = new ThreadLocal<>();

    public void start() {
        traces.set(new ArrayList<>());
    }

    /**
     * Records a trace if a request is currently being traced. Silently
     * no-ops otherwise, rather than throwing — tracing is a nice-to-have
     * for the admin console (Step 9), not something that should be able
     * to break the actual chat response if start() wasn't called for
     * whatever reason.
     */
    public void record(ToolCallTrace trace) {
        List<ToolCallTrace> current = traces.get();
        if (current != null) {
            current.add(trace);
        }
    }

    public List<ToolCallTrace> drainAndClear() {
        List<ToolCallTrace> current = traces.get();
        traces.remove();
        return current == null ? Collections.emptyList() : current;
    }
}
