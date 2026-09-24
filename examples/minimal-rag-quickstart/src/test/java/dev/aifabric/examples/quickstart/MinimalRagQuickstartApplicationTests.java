package dev.aifabric.examples.quickstart;

import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "ai.vector-db.lucene.index-path=target/test-vector-index")
class MinimalRagQuickstartApplicationTests {

    @Autowired
    EmbeddingProvider embeddings;

    @Autowired
    VectorDatabaseService vectors;

    @Test
    void indexesAndFindsOneDocument() {
        var queryVector = embeddings.generateEmbedding(
            AIEmbeddingRequest.builder().text("retrieve a document for RAG").build()
        ).getEmbedding();
        var response = vectors.search(queryVector, AISearchRequest.builder()
            .entityType("guide")
            .limit(1)
            .threshold(0.0)
            .build());

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().getFirst().get("content"))
            .isEqualTo("AI Fabric uses vector embeddings to retrieve relevant documents for semantic search and RAG.");
    }
}
