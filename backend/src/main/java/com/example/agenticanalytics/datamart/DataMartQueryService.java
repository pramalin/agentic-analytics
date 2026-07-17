package com.example.agenticanalytics.datamart;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataMartQueryService {

    private static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final QueryGuard queryGuard;

    public DataMartQueryService(JdbcTemplate jdbcTemplate, QueryGuard queryGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryGuard = queryGuard;
    }

    /** Runs a validated, read-only SELECT and returns rows as maps of column -> value. */
    public List<Map<String, Object>> runQuery(String sql) {
        queryGuard.assertReadOnly(sql);

        List<Map<String, Object>> rows = new ArrayList<>();
        jdbcTemplate.query(sql, resultSet -> {
            var metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            int count = 0;
            while (resultSet.next() && count < MAX_ROWS) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
                }
                rows.add(row);
                count++;
            }
        });
        return rows;
    }

    public List<String> listTables() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = 'public' ORDER BY table_name",
                String.class);
    }

    public List<Map<String, Object>> describeTable(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = 'public' AND table_name = ? " +
                        "ORDER BY ordinal_position",
                tableName);
    }
}
