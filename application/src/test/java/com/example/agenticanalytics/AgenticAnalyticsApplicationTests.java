package com.example.agenticanalytics;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
 *
 * Testcontainers (Step 6): the VectorStore bean now needs a real reachable
 * Postgres with the pgvector extension to initialize its schema on
 * startup — previously this test didn't need a real database at all,
 * relying on whatever happened to be reachable at localhost:5432 (or
 * nothing, if HikariCP's connection validation is more lenient than
 * expected). Rather than keep depending on that, this test now uses the
 * same Testcontainers pattern used elsewhere in this test suite (e.g.
 * SeedDataIT) for a real, predictable Postgres+pgvector instance.
 *
 * withDatabaseName("datamart") is required, not cosmetic: db-init's
 * mcp_reader role setup includes `GRANT CONNECT ON DATABASE datamart`,
 * which hardcodes that literal database name. Testcontainers defaults to a
 * database named "test" otherwise, and that GRANT fails outright —
 * container startup itself fails, not just a later assertion. Latent since
 * Step 5b (when the mcp_reader role was added); never actually triggered
 * until a full `mvn test` run genuinely reached this init script.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.anthropic.api-key=test-placeholder-key",
        "spring.ai.openai.api-key=test-placeholder-key",
        "spring.ai.mcp.client.enabled=false"
})
class AgenticAnalyticsApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("datamart")
                    .withInitScript("db-init/01_init_datamart.sql");

    @Test
    void contextLoads() {
        // Fails if the Spring context can't start — e.g. a missing bean,
        // a bad property, a misconfigured dependency. Cheap and worth keeping
        // even once this project has real business logic in it.
    }
}
