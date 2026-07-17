package com.example.agenticanalytics.datamart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class QueryGuardTest {

    private final QueryGuard queryGuard = new QueryGuard();

    @Test
    void allowsSimpleSelect() {
        assertThatCode(() -> queryGuard.assertReadOnly("SELECT * FROM transaction"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsSelectWithJoinAndWhere() {
        assertThatCode(() -> queryGuard.assertReadOnly(
                "SELECT r.region_name, COUNT(*) FROM transaction t " +
                "JOIN merchant m ON t.merchant_id = m.merchant_id " +
                "JOIN region r ON m.region_id = r.region_id " +
                "WHERE t.status = 'DECLINED' GROUP BY r.region_name"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE transaction",
            "DELETE FROM transaction",
            "UPDATE transaction SET amount = 0",
            "INSERT INTO transaction (amount) VALUES (1)",
            "SELECT * FROM transaction; DROP TABLE transaction",
            "  ",
            "not sql at all"
    })
    void rejectsAnythingNotASimpleSelect(String sql) {
        assertThatThrownBy(() -> queryGuard.assertReadOnly(sql))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> queryGuard.assertReadOnly(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
