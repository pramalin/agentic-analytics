package com.example.agenticanalytics.datamart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the real db-init/01_init_datamart.sql script against a real, ephemeral
 * Postgres container — not H2, not a mock. Uses the exact same script that
 * docker-compose mounts into docker-entrypoint-initdb.d for the "real" stack,
 * so there's only one source of truth for the schema. Catches the class of
 * bug we hit earlier in the seed script (status/decline_reason drawn from
 * independent random values) that a mocked JDBC template never would have.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.anthropic.api-key=test-placeholder-key",
        "spring.ai.mcp.client.enabled=false"
})
class DataMartQueryServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
                    .withInitScript("db-init/01_init_datamart.sql");

    @Autowired
    private DataMartQueryService dataMartQueryService;

    @Test
    void listTablesReturnsSeededSchema() {
        List<String> tables = dataMartQueryService.listTables();
        assertThat(tables).contains("region", "merchant", "transaction");
    }

    @Test
    void describeTableReturnsTransactionColumns() {
        List<Map<String, Object>> columns = dataMartQueryService.describeTable("transaction");
        List<Object> columnNames = columns.stream().map(c -> c.get("column_name")).toList();
        assertThat(columnNames).contains("transaction_id", "merchant_id", "status", "decline_reason");
    }

    @Test
    void runQueryReturnsSeededTransactionRows() {
        List<Map<String, Object>> rows = dataMartQueryService.runQuery(
                "SELECT COUNT(*) AS row_count FROM transaction");
        assertThat(rows).hasSize(1);
        Number rowCount = (Number) rows.get(0).get("row_count");
        assertThat(rowCount.intValue()).isEqualTo(2000);
    }

    @Test
    void declinedRowsAlwaysHaveADeclineReason() {
        // Regression check for the earlier seed-data bug: status and
        // decline_reason must come from the same random draw.
        List<Map<String, Object>> mismatches = dataMartQueryService.runQuery(
                "SELECT COUNT(*) AS row_count FROM transaction " +
                "WHERE (status = 'DECLINED' AND decline_reason IS NULL) " +
                "   OR (status != 'DECLINED' AND decline_reason IS NOT NULL)");
        Number mismatchCount = (Number) mismatches.get(0).get("row_count");
        assertThat(mismatchCount.intValue()).isZero();
    }

    @Test
    void runQueryRejectsWriteStatements() {
        assertThatThrownBy(() -> dataMartQueryService.runQuery("DELETE FROM transaction"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
