package com.example.agenticanalytics.tools;

import com.example.agenticanalytics.datamart.DataMartQueryService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Native Spring AI @Tool bindings — the pattern used before this project
 * switched the agent's tool source to the MCP gateway (see ChatClientConfig
 * and docs/mcp-gateway.md). Kept in the codebase deliberately, as a working
 * example of the simpler in-process alternative: no gateway, no separate
 * container, tools call straight into DataMartQueryService. Not wired into
 * ChatClientConfig anymore, so these methods are currently unused by the
 * running app — DataMartQueryService and QueryGuard, which this class
 * wraps, remain exercised by their own tests regardless.
 */
@Component
public class AgentTools {

    private final DataMartQueryService dataMartQueryService;

    public AgentTools(DataMartQueryService dataMartQueryService) {
        this.dataMartQueryService = dataMartQueryService;
    }

    @Tool(description =
            "List the tables available in the data mart. Call this before writing a query " +
            "if you are not already certain which tables exist.")
    public List<String> listTables() {
        return dataMartQueryService.listTables();
    }

    @Tool(description =
            "Return column names, data types, and nullability for a given table. " +
            "Call this before writing a query against a table you have not already described.")
    public List<Map<String, Object>> describeTable(
            @ToolParam(description = "Exact table name, as returned by listTables") String tableName) {
        return dataMartQueryService.describeTable(tableName);
    }

    @Tool(description =
            "Execute a read-only SQL SELECT against the data mart and return up to 200 rows. " +
            "Only SELECT statements are permitted; anything else is rejected. " +
            "Always call describeTable first for any table referenced in the query.")
    public List<Map<String, Object>> runQuery(
            @ToolParam(description = "A single, valid, read-only SQL SELECT statement") String sql) {
        return dataMartQueryService.runQuery(sql);
    }
}
