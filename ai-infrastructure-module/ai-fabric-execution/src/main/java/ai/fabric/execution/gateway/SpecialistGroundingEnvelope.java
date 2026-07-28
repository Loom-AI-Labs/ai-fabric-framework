package ai.fabric.execution.gateway;

import java.util.List;

/**
 * Bounded, provider-neutral grounding supplied to structured finalization.
 */
public record SpecialistGroundingEnvelope(
    List<ResultExcerpt> results,
    List<EvidenceExcerpt> evidence,
    boolean truncated
) {
    public SpecialistGroundingEnvelope {
        results = results == null ? List.of() : List.copyOf(results);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public record ResultExcerpt(String resultType, String text) {}

    public record EvidenceExcerpt(
        String evidenceId,
        String content,
        Double relevanceScore,
        String source,
        String sourceUrl,
        String vectorSpace
    ) {}
}
