package ai.fabric.rag;

import java.util.Objects;

/** Retrieval policy, never an authorization scope or a provider credential. */
public record RetrievalProfile(Mode mode, int vectorCandidates, int lexicalCandidates,
                               int maxCandidates, int maxResults, int maxContextCharacters, int rankConstant,
                               FailurePolicy failurePolicy) {
    public enum Mode { DENSE, HYBRID, KEYWORD }
    public enum FailurePolicy { FAIL, VISIBLE_DENSE_FALLBACK }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RetrievalProfile {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        // These are finite retrieval-work bounds, not semantic acceptance gates.
        bounded(vectorCandidates, 1, 100, "vectorCandidates");
        bounded(lexicalCandidates, 1, 100, "lexicalCandidates");
        bounded(maxCandidates, 1, 200, "maxCandidates");
        bounded(maxResults, 1, maxCandidates, "maxResults");
        bounded(maxContextCharacters, 256, 200000, "maxContextCharacters");
        bounded(rankConstant, 1, 1000, "rankConstant");
    }

    public RetrievalProfile(Mode mode, int vectorCandidates, int lexicalCandidates, int maxCandidates,
                            int maxResults, int rankConstant, FailurePolicy failurePolicy) {
        this(mode, vectorCandidates, lexicalCandidates, maxCandidates, maxResults, 8000, rankConstant, failurePolicy);
    }

    public static RetrievalProfile dense() {
        return new RetrievalProfile(Mode.DENSE, 20, 20, 40, 6, 60, FailurePolicy.FAIL);
    }

    private static void bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
