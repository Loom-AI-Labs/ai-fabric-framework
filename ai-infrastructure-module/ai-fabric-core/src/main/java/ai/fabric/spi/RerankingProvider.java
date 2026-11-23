package ai.fabric.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Receives only caller-authorized excerpts. Results reference inputs; they cannot supply evidence. */
public interface RerankingProvider {
    String providerId();
    String modelId();
    Result rerank(Request request);

    record Candidate(String reference, String text) {
        public Candidate {
            if (reference == null || reference.isBlank() || reference.length() > 512
                || text == null || text.isBlank() || text.length() > 16000) {
                throw new IllegalArgumentException("Reranking candidate requires a bounded reference and approved excerpt");
            }
        }
        @Override public String toString() { return "RerankingCandidate[reference=" + reference + ", characters=" + text.length() + "]"; }
    }

    record Request(String query, List<Candidate> candidates, Duration timeout, int maxTokensPerDocument) {
        public Request {
            if (query == null || query.isBlank() || query.length() > 4000 || candidates == null
                || candidates.isEmpty() || candidates.size() > 100 || timeout == null || timeout.isNegative()
                || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0 || timeout.toMillis() < 1
                || maxTokensPerDocument < 1 || maxTokensPerDocument > 4096) {
                throw new IllegalArgumentException("Reranking request exceeds its finite work allowance");
            }
            candidates = List.copyOf(candidates);
            if (candidates.stream().map(Candidate::reference).distinct().count() != candidates.size()
                || candidates.stream().mapToInt(candidate -> candidate.text().length()).sum() > 200000) {
                throw new IllegalArgumentException("Reranking references must be unique and total excerpt size bounded");
            }
        }
        @Override public String toString() { return "RerankingRequest[candidates=" + candidates.size() + "]"; }
    }

    record RankedCandidate(String reference, double relevance) {
        public RankedCandidate {
            Objects.requireNonNull(reference);
            if (!Double.isFinite(relevance)) throw new IllegalArgumentException("Reranking score must be finite");
        }
    }

    record Usage(Map<String, Long> billedUnits) {
        public Usage {
            billedUnits = Map.copyOf(billedUnits);
            if (billedUnits.isEmpty() || billedUnits.size() > 16
                || billedUnits.values().stream().anyMatch(value -> value < 0)) {
                throw new IllegalArgumentException("Actual reranking billing units are required");
            }
        }
    }

    record Result(List<RankedCandidate> candidates, Usage usage, String providerRequestId) {
        public Result { candidates = List.copyOf(candidates); Objects.requireNonNull(usage); }
    }
}
