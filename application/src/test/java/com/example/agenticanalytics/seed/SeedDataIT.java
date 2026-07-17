package com.example.agenticanalytics.seed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs db-init/01_init_datamart.sql against a real Postgres and checks the
 * seed data's internal consistency — plain JdbcTemplate, no application
 * service layer needed for this. The one check preserved here
 * (declinedRowsAlwaysHaveADeclineReason) is a direct regression test for a
 * real bug from Step 4: the original seed script drew status and
 * decline_reason from independent RANDOM() calls, so a row could end up
 * DECLINED with a null decline_reason, or non-DECLINED with one set. Worth
 * keeping even after AgentTools/DataMartQueryService/QueryGuard were
 * removed as dead code (Step 6) — this test was never about that
 * application layer, only about the seed data being self-consistent.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.anthropic.api-key=test-placeholder-key",
        "spring.ai.mcp.client.enabled=false"
})
class SeedDataIT {

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void seedsExpectedTransactionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction", Integer.class);
        assertThat(count).isEqualTo(2000);
    }

    @Test
    void declinedRowsAlwaysHaveADeclineReason() {
        Integer mismatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction " +
                "WHERE (status = 'DECLINED' AND decline_reason IS NULL) " +
                "   OR (status != 'DECLINED' AND decline_reason IS NOT NULL)",
                Integer.class);
        assertThat(mismatches).isZero();
    }
}
