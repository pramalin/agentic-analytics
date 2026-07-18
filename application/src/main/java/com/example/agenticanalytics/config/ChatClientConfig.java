package com.example.agenticanalytics.config;

import java.util.Arrays;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.agenticanalytics.tracing.ToolCallTraceCollector;
import com.example.agenticanalytics.tracing.TracingToolCallback;

@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            You are a data analyst agent for an internal analytics platform.
            You answer plain-English questions by querying a read-only Postgres data mart
            through tools exposed over MCP: list_tables, describe_table, execute_sql.

            You may also see retrieved schema documentation included in context below —
            plain-English notes on what tables/columns mean and, importantly, the exact
            value conventions used (e.g. whether a status column is uppercase). Treat
            that documentation as authoritative fact, not a suggestion to verify — if it
            tells you a column's values are uppercase, use that casing directly rather
            than re-deriving it yourself.

            Rules you must follow, in order, every single time, with no exceptions:
            0. Every new question requires its own fresh execute_sql call, even if a
               previous question in this same conversation covered similar or related
               ground. Never answer from a prior turn's results, and never respond
               "I don't know" without having attempted at least one execute_sql call
               for the current question first. Skipping this because the conversation
               already feels "explored" is exactly the failure mode this rule exists
               to prevent.
            1. Call list_tables first, unless you have already called it earlier in this
               conversation. Never assume you already know the table names. (This
               permission to skip applies only to list_tables/describe_table — it does
               NOT extend to execute_sql; see rule 0.)
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
            stored in a column, and the retrieved documentation may not cover every
            column either. Before EVERY WHERE clause that filters on a text/varchar
            column whose exact values aren't confirmed by either source, run a SELECT
            DISTINCT on that column first, in this same conversation — even if the
            column name seems self-explanatory. This is not optional and not a judgment
            call: skipping it because a value like "declined" seems obvious is exactly
            how a silent casing mismatch produces a wrong answer that looks like a
            legitimate empty result. A WHERE clause that matches zero rows because of a
            casing or spelling mismatch is a correctness bug, not an empty result — if a
            filtered query returns nothing, re-verify the exact filter value before
            reporting "no data found."

            When your answer contains multiple rows of comparable data (e.g. a
            breakdown by region, status, or category), format it as a Markdown
            table rather than a bulleted list — the frontend renders tables
            properly, and a table communicates a comparison more clearly than
            a list does.

            If a question is ambiguous (e.g. an unspecified date range), state the
            assumption you're making rather than asking the user to restate the question.
            When you return results, briefly explain what the numbers mean in plain
            English in addition to any structured data. If a query fails, say so plainly
            and explain why if you can — rewrite and retry once using what describe_table
            told you, rather than fabricating a plausible-looking answer.
            """;

    @Bean
    public ChatMemory chatMemory() {
        // 20 was too small once real tool-calling entered the picture — a
        // single Q&A turn with list_tables + describe_table + execute_sql
        // easily consumes 6-8 messages (question, tool-call requests, tool
        // results, final answer), so the window could exhaust after just 2
        // real questions, truncating mid-tool-call-sequence and confusing
        // the model on later turns.
        return MessageWindowChatMemory.builder().maxMessages(100).build();
    }

    /**
     * Tools come from the MCP gateway (compose.yaml's mcp-gateway service),
     * not from an in-process @Tool class — see docs/mcp-gateway.md for why.
     * {@code mcpTools} is an {@link ObjectProvider} rather than a direct
     * injection so the app (and its tests) still start cleanly if the MCP
     * client is disabled or the gateway isn't reachable; the agent would
     * just have no tools available in that case, which is a degraded mode,
     * not a startup failure.
     *
     * RAG (Step 6): schema docs embedded by SchemaDocIngestor are retrieved
     * via VectorStoreDocumentRetriever and injected into the prompt by
     * RetrievalAugmentationAdvisor, ahead of the memory advisor in the
     * chain — the model should see grounded schema facts before its own
     * conversation history influences the response.
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder,
                                  ChatMemory chatMemory,
                                  VectorStore vectorStore,
                                  ObjectProvider<ToolCallbackProvider> mcpTools,
                                  ToolCallTraceCollector traceCollector) {    
        ToolCallback[] toolCallbacks = mcpTools.stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(callback -> (ToolCallback) new TracingToolCallback(callback, traceCollector))
                .toArray(ToolCallback[]::new);

        var documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(5)
                .build();

        var ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();

        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools((Object[]) toolCallbacks)
                .defaultAdvisors(
                       // ragAdvisor,   // temporarily disabled to isolate turn-2 tool-calling bug
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }
}
