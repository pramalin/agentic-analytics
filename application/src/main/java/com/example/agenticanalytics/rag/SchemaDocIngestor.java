package com.example.agenticanalytics.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On startup, embeds every doc under classpath:schema-docs/ into the vector
 * store. These are plain-English descriptions of what each table/column
 * means — including value conventions describe_table can't tell you (see
 * transaction.md: transaction.status is uppercase, a fact that caused a
 * real bug in Step 5i/5j before this existed). The agent grounds itself
 * here before deciding which tables to query and what values to filter on.
 *
 * Re-ingests on every startup rather than checking a content hash first —
 * fine for a handful of small local docs with a free local embedding
 * model; would need hash-checking before this scaled to a real deployment.
 *
 * Gated behind app.rag.enabled (default false): the RetrievalAugmentationAdvisor
 * this feeds is currently disabled in ChatClientConfig (see docs/rag.md —
 * it broke multi-turn tool-calling), so nothing has read these embeddings
 * since then. Running this unconditionally anyway would mean real,
 * unnecessary OpenAI embedding API calls on every single startup — cost
 * for a feature nothing uses — and, as found via a real test failure
 * (401 from a placeholder key in a context-load test), makes this class
 * an unexpected active network caller in any test that boots the full
 * context. Set app.rag.enabled=true once RAG is actually re-enabled.
 */
@Component
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class SchemaDocIngestor implements ApplicationRunner {

    private final VectorStore vectorStore;

    public SchemaDocIngestor(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:schema-docs/*.md");

        // All TokenTextSplitter constructors are deprecated in this version —
        // builder() is the only current way to construct one (verified, this
        // is the second deprecation warning caught the hard way on this one
        // class; the no-arg AND the single-boolean-arg constructors are both
        // deprecated, not just the no-arg one).
        var splitter = TokenTextSplitter.builder().build();
        for (Resource resource : resources) {
            TextReader reader = new TextReader(resource);
            reader.getCustomMetadata().put("source", resource.getFilename());
            List<Document> chunks = splitter.apply(reader.get());
            vectorStore.add(chunks);
        }
    }
}
