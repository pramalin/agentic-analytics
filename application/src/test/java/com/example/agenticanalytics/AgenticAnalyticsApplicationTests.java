package com.example.agenticanalytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full context-load smoke test. Supplies a placeholder Anthropic key so
 * AnthropicAutoConfiguration's startup validation (Assert.hasText on the
 * key) passes — the key is never actually used, since building the
 * AnthropicChatModel bean doesn't make a network call. This is deliberately
 * NOT set via a fallback in application.yml, because the real app should
 * fail fast on a missing key rather than silently start broken.
 *
 * Also disables the MCP client: no mcp-gateway container is running during
 * `mvn test`, and ChatClientConfig is written defensively (ObjectProvider)
 * specifically so this doesn't break context loading.
 *
 * Explicitly sets spring.ai.model.chat=anthropic — Spring AI 2.0's actual
 * multi-provider selector (both AnthropicChatAutoConfiguration and
 * OllamaChatAutoConfiguration key off this one property, with
 * matchIfMissing=true on both, so leaving it unset activates both and
 * ChatClientAutoConfiguration fails with "expected single matching bean
 * but found 2"). The Anthropic side just needs the placeholder key to
 * pass startup validation — no real network call happens at bean creation.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.anthropic.api-key=test-placeholder-key",
        "spring.ai.mcp.client.enabled=false"
})
class AgenticAnalyticsApplicationTests {

    @Test
    void contextLoads() {
        // Fails if the Spring context can't start — e.g. a missing bean,
        // a bad property, a misconfigured dependency. Cheap and worth keeping
        // even once this project has real business logic in it.
    }
}
