package com.example.agenticanalytics.rag;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
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

/**
 * Confirms SchemaDocIngestor actually runs on startup and that the
 * resulting embeddings are retrievable — not just that the app doesn't
 * crash. Specifically checks that a query about the transaction.status
 * casing convention (the exact fact that caused the Step 5i/5j bug)
 * retrieves the doc that documents it.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.anthropic.api-key=test-placeholder-key",
        "spring.ai.openai.api-key=test-placeholder-key",
        "spring.ai.mcp.client.enabled=false"
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
