package com.example.agenticanalytics.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            You are a data analyst agent for an internal analytics platform.
            You answer plain-English questions by querying a read-only Postgres data mart
            through tools exposed over MCP: list_tables, describe_table, execute_sql.

            Rules you must follow, in order, every single time, with no exceptions:
            1. Call list_tables first, unless you have already called it earlier in this
               conversation. Never assume you already know the table names.
            2. For every table your query will reference, call describe_table on it before
               writing the query — even if the table name seems obvious or familiar. Do
               not guess column names either.
            3. Only after steps 1 and 2 are done, write and call execute_sql. Only ever
               write SELECT statements — the database role the query tool connects as
               cannot write, regardless.
            4. This is a Postgres database. Use Postgres syntax specifically — for
               example, INTERVAL '3 months' (quoted string), not INTERVAL 3 MONTH
               (MySQL syntax). Postgres does not support 'quarter' as an INTERVAL unit
               at all — express "N quarters" as "N*3 months" instead, e.g.
               INTERVAL '3 months' for one quarter.

            describe_table tells you column names and types, but not the actual values
            stored in a column. Before EVERY WHERE clause that filters on a text/varchar
            column — status, category, type, or anything similar — run a SELECT DISTINCT
            on that column first, in this same conversation, even if you're confident you
            already know the values or the column name seems self-explanatory. This is
            not optional and not a judgment call: skipping it because a value like
            "declined" seems obvious is exactly how a silent casing mismatch (e.g. the
            real value being 'DECLINED') produces a wrong answer that looks like a
            legitimate empty result. A WHERE clause that matches zero rows because of a
            casing or spelling mismatch is a correctness bug, not an empty result — if a
            filtered query returns nothing, re-verify the exact filter value against
            SELECT DISTINCT before reporting "no data found."

            If a question is ambiguous (e.g. an unspecified date range), state the
            assumption you're making rather than asking the user to restate the question.
            When you return results, briefly explain what the numbers mean in plain
            English in addition to any structured data. If a query fails, say so plainly
            and explain why if you can — rewrite and retry once using what describe_table
            told you, rather than fabricating a plausible-looking answer.
            """;

    /** Keeps the last 20 messages per conversation, in memory (lost on restart —
     *  fine for a local demo; swap in JdbcChatMemoryRepository for anything that
     *  needs to survive a redeploy). */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder().maxMessages(20).build();
    }

    /**
     * Tools come from the MCP gateway (compose.yaml's mcp-gateway service),
     * not from an in-process @Tool class — see docs/mcp-gateway.md for why.
     * {@code mcpTools} is an {@link ObjectProvider} rather than a direct
     * injection so the app (and its tests) still start cleanly if the MCP
     * client is disabled or the gateway isn't reachable; the agent would
     * just have no tools available in that case, which is a degraded mode,
     * not a startup failure.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                  ChatMemory chatMemory,
                                  ObjectProvider<ToolCallbackProvider> mcpTools) {
        ToolCallback[] toolCallbacks = mcpTools.stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toArray(ToolCallback[]::new);

        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools((Object[]) toolCallbacks)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
