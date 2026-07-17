package com.example.agenticanalytics.datamart;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Last line of defense before a (later, model-generated) query touches the
 * data mart. Deliberately conservative: reject anything that isn't a single,
 * simple read-only SELECT. This is not a full SQL parser — it's a tripwire,
 * and it fails closed on anything it doesn't recognise. Not a substitute for
 * a real read-only DB role in production, but a reasonable first gate.
 */
@Component
public class QueryGuard {

    private static final Pattern SELECT_ONLY =
            Pattern.compile("^\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);

    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "TRUNCATE",
            "CREATE", "GRANT", "REVOKE", "MERGE", "CALL", "EXEC", ";"
    );

    public void assertReadOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Empty query.");
        }
        if (!SELECT_ONLY.matcher(sql).find()) {
            throw new IllegalArgumentException("Only SELECT statements are permitted.");
        }
        String upper = sql.toUpperCase();
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (upper.contains(keyword)) {
                throw new IllegalArgumentException(
                        "Query rejected: contains disallowed keyword or statement separator '" + keyword + "'.");
            }
        }
    }
}
