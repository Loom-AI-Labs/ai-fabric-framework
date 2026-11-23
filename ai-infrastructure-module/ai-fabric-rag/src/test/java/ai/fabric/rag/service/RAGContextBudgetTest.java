package ai.fabric.rag.service;

import ai.fabric.dto.RAGResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RAGContextBudgetTest {
    private final RAGDocumentMapper mapper = new RAGDocumentMapper();

    @Test
    void characterBudgetIncludesAttributionAndHeadersAndDoesNotChangeOriginal() {
        var original = document("chunk-a", "approved evidence ".repeat(100));
        var result = mapper.selectContext(List.of(original), 6, 256);
        assertThat(result.context()).hasSize(256);
        assertThat(result.documents()).hasSize(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.documents().getFirst().getMetadata()).containsEntry("retrievalContextTruncated", true);
        assertThat(original.getContent()).hasSizeGreaterThan(256);
        assertThat(original.getMetadata()).doesNotContainKey("retrievalContextTruncated");
    }

    @Test
    void preservesRankOrderAndHonorsTotalItemLimit() {
        var result = mapper.selectContext(List.of(document("a", "one"), document("b", "two")), 1, 8000);
        assertThat(result.documents()).extracting(RAGResponse.RAGDocument::getId).containsExactly("a");
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void neverDropsAttributionToSqueezeInAnOversizedIdentifier() {
        var result = mapper.selectContext(List.of(document("a".repeat(1000), "fact")), 6, 256);
        assertThat(result.documents()).isEmpty();
        assertThat(result.context().length()).isLessThanOrEqualTo(256);
    }

    private static RAGResponse.RAGDocument document(String id, String content) {
        return RAGResponse.RAGDocument.builder().id(id).type("document").source("approved-guides")
            .content(content).metadata(Map.of("knowledgeSourceId", "approved-guides")).score(.02).build();
    }
}
