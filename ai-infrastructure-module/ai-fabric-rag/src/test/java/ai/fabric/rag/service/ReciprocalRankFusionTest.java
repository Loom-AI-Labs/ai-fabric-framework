package ai.fabric.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReciprocalRankFusionTest {
    @Test
    void overlappingRanksWinWithoutComparingBm25ToCosine() {
        var vector = list("s", "product", "vector", row("a", .9), row("b", .8));
        var lexical = list("s", "product", "lexical", row("c", 100), row("b", 50));
        var result = ReciprocalRankFusion.fuse(List.of(vector, lexical), 60, 10);
        assertThat(result).extracting(r -> r.get("id")).containsExactly("b", "a", "c");
        Map<?, ?> scores = (Map<?, ?>) ((Map<?, ?>) result.getFirst().get("metadata")).get("retrievalScores");
        assertThat(scores.get("vectorScore")).isEqualTo(.8);
        assertThat(scores.get("lexicalScore")).isEqualTo(50.0);
        assertThat(scores.get("scoreType")).isEqualTo("RRF");
        assertThat(result.get(2).get("similarity")).isEqualTo(0.0);
    }

    @Test
    void duplicateRowsDoNotVoteTwiceOrConsumeRanks() {
        var result = ReciprocalRankFusion.fuse(List.of(list("s", "product", "vector",
            row("a", .9), row("a", .9), row("b", .8))), 60, 10);
        assertThat(result).hasSize(2);
        assertThat(result.get(1).get("score")).isEqualTo(1.0 / 62);
    }

    @Test
    void identityRetainsSourceEntityTypeVersionAndChunk() {
        var base = row("a", .9);
        var old = Map.<String, Object>of("id", "a", "content", "a", "score", .9,
            "metadata", Map.of("_aiDocumentSourceId", "doc", "_aiDocumentSourceVersion", 1, "_aiDocumentChunkId", "c1"));
        var updated = Map.<String, Object>of("id", "a", "content", "a", "score", .9,
            "metadata", Map.of("_aiDocumentSourceId", "doc", "_aiDocumentSourceVersion", 2, "_aiDocumentChunkId", "c1"));
        var result = ReciprocalRankFusion.fuse(List.of(list("s1", "product", "vector", base),
            list("s2", "product", "vector", base), list("s1", "vehicle", "vector", base),
            list("s1", "document", "vector", old, updated)), 60, 10);
        assertThat(result).hasSize(5);
    }

    @Test
    void tiesAreDeterministicAndInputsRemainUntouched() {
        var a = row("a", .8);
        var b = row("b", .9);
        var first = ReciprocalRankFusion.fuse(List.of(list("s", "product", "vector", b),
            list("s", "product", "lexical", a)), 60, 10);
        var reversed = ReciprocalRankFusion.fuse(List.of(list("s", "product", "lexical", a),
            list("s", "product", "vector", b)), 60, 10);
        assertThat(first).isEqualTo(reversed);
        assertThat(a).containsEntry("score", .8).doesNotContainKey("similarity");
    }

    @Test
    void conflictingContentAndMissingIdentityAreExecutionFailures() {
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(List.of(list("s", "product", "vector", row("a", .9)),
            list("s", "product", "lexical", Map.of("id", "a", "content", "different"))), 60, 10))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(List.of(list("s", "product", "vector",
            Map.of("content", "missing identity"))), 60, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void denseBaselinePreservesOriginalScoresAndSortsAcrossSources() {
        var result = ReciprocalRankFusion.rankSingleLeg(List.of(list("s1", "product", "vector", row("a", .1)),
            list("s2", "product", "vector", row("b", .9))), 10);
        assertThat(result).extracting(r -> r.get("id")).containsExactly("b", "a");
        assertThat(result.getFirst().get("score")).isEqualTo(.9);
    }

    @Test
    void conflictingDuplicatesAreRejectedWithinTheSameLegAsWell() {
        var conflicting = Map.<String, Object>of("id", "a", "content", "different", "score", .8);
        var duplicates = List.of(list("s", "product", "vector", row("a", .9), conflicting));
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(duplicates, 60, 10))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReciprocalRankFusion.rankSingleLeg(duplicates, 10))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReciprocalRankFusion.rankSingleLeg(List.of(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> row(String id, double score) {
        return Map.of("id", id, "content", id, "score", score, "metadata", Map.of());
    }

    @SafeVarargs
    private static ReciprocalRankFusion.RankedList list(String source, String type, String leg, Map<String, Object>... rows) {
        return new ReciprocalRankFusion.RankedList(source, type, leg, List.of(rows));
    }
}
