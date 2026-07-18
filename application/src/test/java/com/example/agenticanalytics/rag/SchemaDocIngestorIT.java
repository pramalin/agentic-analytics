package com.example.agenticanalytics.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms SchemaDocIngestor actually runs on startup and that the
 * resulting embeddings are retrievable — not just that the app doesn't
 * crash. Specifically checks that a query about the transaction.status
 * casing convention (the exact fact that caused the Step 5i/5j bug)
 * retrieves the doc that documents it.
 *
 * Two things this test needs that the other context-load tests don't:
 *
 * app.rag.enabled=true — SchemaDocIngestor is gated off by default (see
 * its own javadoc) since RAG is currently disabled; this test is
 * specifically about that ingestor, so it needs to turn the gate on.
 *
 * @EnabledIfEnvironmentVariable(OPENAI_API_KEY) — unlike the placeholder
 * keys used elsewhere in this test suite (which only need to satisfy
 * Assert.hasText for bean creation, never making a real call), this test
 * does a genuine embed-and-retrieve — a fake key can't produce a
 * meaningful result here, only a 401. Rather than fail for anyone running
 * `mvn test` without a real key (breaking this project's established
 * "tests never need a real key" convention), this test skips itself
 * entirely when none is present, and only actually verifies something
 * when a real key is available.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@SpringBootTest(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.anthropic.api-key=test-placeholder-key",
        "spring.ai.mcp.client.enabled=false",
        "app.rag.enabled=true"
})
class SchemaDocIngestorIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    // Required, not cosmetic — db-init's mcp_reader role setup
                    // hardcodes `GRANT CONNECT ON DATABASE datamart`, which fails
                    // outright against Testcontainers' default "test" database.
                    // See AgenticAnalyticsApplicationTests for the full story.
                    .withDatabaseName("datamart")
                    .withInitScript("db-init/01_init_datamart.sql");

    @Autowired
    private VectorStore vectorStore;

    @Test
    void schemaDocsAreEmbeddedAndRetrievable() {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("what casing are transaction status values stored in")
                        .topK(3)
                        .build());

        assertThat(results).isNotEmpty();
        assertThat(results)
                .anyMatch(doc -> doc.getText() != null
                        && doc.getText().toUpperCase().contains("UPPERCASE"));
    }
}
