package ai.fabric.execution.gateway;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.intent.orchestration.OrchestrationResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects only sanitized response text and canonical evidence into model grounding.
 */
public final class SpecialistGroundingProjector {

    private static final List<String> APPROVED_TEXT_KEYS =
        List.of("answer", "summary");
    private static final String READ_ACTION_RESOLUTION_KEY =
        "readActionResolution";
    private static final String EXECUTED_ACTIONS_KEY = "executedActions";
    private static final String ACTION_KEY = "action";
    private static final String GROUNDING_USABLE_KEY = "groundingUsable";
    private static final String EVIDENCE_SUMMARY_KEY = "evidenceSummary";

    public SpecialistGroundingEnvelope project(
        OrchestrationResult result,
        List<AIEvidenceReference> evidence,
        int maxCharacters
    ) {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException(
                "maxCharacters must be positive"
            );
        }
        List<AIEvidenceReference> safeEvidence =
            evidence == null ? List.of() : List.copyOf(evidence);
        int resultBudget = safeEvidence.isEmpty()
            ? maxCharacters
            : Math.max(1, (maxCharacters * 3) / 5);
        int evidenceBudget = Math.max(0, maxCharacters - resultBudget);

        ProjectionBudget results = new ProjectionBudget(resultBudget);
        List<SpecialistGroundingEnvelope.ResultExcerpt> resultExcerpts =
            new ArrayList<>();
        collectResult(result, results, resultExcerpts, new LinkedHashSet<>());

        ProjectionBudget evidenceProjection = new ProjectionBudget(evidenceBudget);
        List<SpecialistGroundingEnvelope.EvidenceExcerpt> evidenceExcerpts =
            new ArrayList<>();
        for (AIEvidenceReference reference : safeEvidence) {
            String content = evidenceProjection.take(reference.content());
            if (content == null) {
                break;
            }
            evidenceExcerpts.add(new SpecialistGroundingEnvelope.EvidenceExcerpt(
                reference.evidenceId(),
                content,
                reference.relevanceScore(),
                reference.source(),
                reference.sourceUrl(),
                reference.vectorSpace()
            ));
        }

        return new SpecialistGroundingEnvelope(
            resultExcerpts,
            evidenceExcerpts,
            results.truncated() || evidenceProjection.truncated()
        );
    }

    private void collectResult(
        OrchestrationResult result,
        ProjectionBudget budget,
        List<SpecialistGroundingEnvelope.ResultExcerpt> excerpts,
        Set<String> seen
    ) {
        if (result == null || budget.exhausted()) {
            return;
        }
        String resultType = result.getType() != null
            ? result.getType().name()
            : "UNKNOWN";
        addExcerpt(resultType, result.getMessage(), budget, excerpts, seen);

        Map<String, Object> data = result.getData();
        if (data != null) {
            for (String key : APPROVED_TEXT_KEYS) {
                Object value = data.get(key);
                if (value instanceof String text) {
                    addExcerpt(
                        resultType + "." + key,
                        text,
                        budget,
                        excerpts,
                        seen
                    );
                }
            }
            collectReadActionFacts(data, budget, excerpts, seen);
        }
        if (result.getChildren() != null) {
            for (OrchestrationResult child : result.getChildren()) {
                collectResult(child, budget, excerpts, seen);
                if (budget.exhausted()) {
                    break;
                }
            }
        }
    }

    private void collectReadActionFacts(
        Map<String, Object> data,
        ProjectionBudget budget,
        List<SpecialistGroundingEnvelope.ResultExcerpt> excerpts,
        Set<String> seen
    ) {
        Object rawResolution = data.get(READ_ACTION_RESOLUTION_KEY);
        if (!(rawResolution instanceof Map<?, ?> resolution)) {
            return;
        }
        Object rawActions = resolution.get(EXECUTED_ACTIONS_KEY);
        if (!(rawActions instanceof List<?> actions)) {
            return;
        }
        for (Object rawAction : actions) {
            if (budget.exhausted()) {
                return;
            }
            if (!(rawAction instanceof Map<?, ?> action)
                || !Boolean.TRUE.equals(action.get(GROUNDING_USABLE_KEY))) {
                continue;
            }
            Object rawSummary = action.get(EVIDENCE_SUMMARY_KEY);
            if (!(rawSummary instanceof String summary)
                || summary.isBlank()) {
                continue;
            }
            Object rawName = action.get(ACTION_KEY);
            String actionName = rawName instanceof String name
                && !name.isBlank()
                    ? name.trim()
                    : "unknown";
            addExcerpt(
                "READ_ACTION_FACTS." + actionName,
                summary,
                budget,
                excerpts,
                seen
            );
        }
    }

    private void addExcerpt(
        String resultType,
        String value,
        ProjectionBudget budget,
        List<SpecialistGroundingEnvelope.ResultExcerpt> excerpts,
        Set<String> seen
    ) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        if (!seen.add(normalized)) {
            return;
        }
        String text = budget.take(normalized);
        if (text != null) {
            excerpts.add(
                new SpecialistGroundingEnvelope.ResultExcerpt(resultType, text)
            );
        }
    }

    private static final class ProjectionBudget {

        private int remaining;
        private boolean truncated;

        private ProjectionBudget(int remaining) {
            this.remaining = Math.max(0, remaining);
        }

        private String take(String value) {
            if (value == null || value.isBlank() || remaining == 0) {
                if (value != null && !value.isBlank()) {
                    truncated = true;
                }
                return null;
            }
            String normalized = value.trim();
            if (normalized.length() <= remaining) {
                remaining -= normalized.length();
                return normalized;
            }
            String clipped = normalized.substring(0, remaining);
            remaining = 0;
            truncated = true;
            return clipped;
        }

        private boolean exhausted() {
            return remaining == 0;
        }

        private boolean truncated() {
            return truncated;
        }
    }
}
