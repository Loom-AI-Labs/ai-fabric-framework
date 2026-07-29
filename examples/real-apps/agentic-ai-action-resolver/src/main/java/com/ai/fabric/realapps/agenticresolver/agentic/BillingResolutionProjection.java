package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.intent.orchestration.OrchestrationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

final class BillingResolutionProjection {

    private final ObjectMapper objectMapper;

    BillingResolutionProjection(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void validateGrounding(OrchestrationResult result) {
        canonicalResult(authoritativeFacts(result));
    }

    JsonNode project(OrchestrationResult result) {
        return objectMapper.valueToTree(
            canonicalResult(authoritativeFacts(result))
        );
    }

    void validateFinalOutput(
        JsonNode output,
        OrchestrationResult sourceResult
    ) {
        BillingResolutionAssessmentResult actual;
        try {
            actual = objectMapper.treeToValue(
                output,
                BillingResolutionAssessmentResult.class
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                "Billing assessment output is invalid",
                ex
            );
        }
        BillingResolutionAssessmentResult expected = canonicalResult(
            authoritativeFacts(sourceResult)
        );
        if (!equivalent(actual, expected)) {
            throw new IllegalArgumentException(
                "Billing assessment conflicts with authoritative policy facts"
            );
        }
    }

    private BillingResolutionAssessmentResult canonicalResult(
        Map<String, Object> facts
    ) {
        return new BillingResolutionAssessmentResult(
            enumValue(
                BillingResolutionAssessmentResult.class,
                com.ai.fabric.realapps.agenticresolver.entity.RefundRequest
                    .ResolutionType.class,
                requiredString(facts, "resolutionType")
            ),
            requiredDecimal(facts, "amount"),
            enumValue(
                BillingResolutionAssessmentResult.class,
                BillingResolutionAssessmentResult.Decision.class,
                requiredString(facts, "decision")
            ),
            enumValue(
                BillingResolutionAssessmentResult.class,
                BillingResolutionAssessmentResult.ExpectedStatus.class,
                requiredString(facts, "expectedStatus")
            ),
            requiredDecimal(facts, "automaticLimit"),
            requiredString(facts, "explanation")
        );
    }

    private boolean equivalent(
        BillingResolutionAssessmentResult actual,
        BillingResolutionAssessmentResult expected
    ) {
        return actual != null
            && actual.resolutionType() == expected.resolutionType()
            && decimalEquals(actual.amount(), expected.amount())
            && actual.decision() == expected.decision()
            && actual.expectedStatus() == expected.expectedStatus()
            && decimalEquals(
                actual.automaticLimit(),
                expected.automaticLimit()
            )
            && expected.explanation().equals(actual.explanation());
    }

    private boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left != null
            && right != null
            && left.compareTo(right) == 0;
    }

    private Map<String, Object> authoritativeFacts(
        OrchestrationResult result
    ) {
        if (result == null || result.getData() == null) {
            throw missingFacts();
        }
        Object rawResolution = result.getData().get("readActionResolution");
        if (!(rawResolution instanceof Map<?, ?> resolution)
            || !(resolution.get("executedActions")
                instanceof List<?> actions)) {
            throw missingFacts();
        }
        for (Object rawAction : actions) {
            if (!(rawAction instanceof Map<?, ?> action)
                || !AccountResolverSpecialists.BILLING_ASSESSMENT_ACTION
                    .equals(action.get("action"))
                || !Boolean.TRUE.equals(action.get("groundingUsable"))) {
                continue;
            }
            Object rawSummary = action.get("evidenceSummary");
            if (!(rawSummary instanceof String summary)
                || summary.isBlank()) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> facts = objectMapper.readValue(
                    summary,
                    Map.class
                );
                canonicalFields(facts);
                return facts;
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException(
                    "Authoritative billing assessment facts are invalid",
                    ex
                );
            }
        }
        throw missingFacts();
    }

    private void canonicalFields(Map<String, Object> facts) {
        requiredString(facts, "resolutionType");
        requiredDecimal(facts, "amount");
        requiredString(facts, "decision");
        requiredString(facts, "expectedStatus");
        requiredDecimal(facts, "automaticLimit");
        requiredString(facts, "explanation");
    }

    private String requiredString(
        Map<String, Object> facts,
        String name
    ) {
        Object value = facts != null ? facts.get(name) : null;
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(
                "Authoritative billing fact is missing: " + name
            );
        }
        return text.trim();
    }

    private BigDecimal requiredDecimal(
        Map<String, Object> facts,
        String name
    ) {
        Object value = facts != null ? facts.get(name) : null;
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw new IllegalArgumentException(
                "Authoritative billing fact is missing: " + name
            );
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                "Authoritative billing fact is invalid: " + name,
                ex
            );
        }
    }

    private <E extends Enum<E>> E enumValue(
        Class<?> owner,
        Class<E> type,
        String value
    ) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Unsupported " + owner.getSimpleName()
                    + " value: " + value,
                ex
            );
        }
    }

    private IllegalArgumentException missingFacts() {
        return new IllegalArgumentException(
            "Authoritative billing assessment facts are required"
        );
    }
}
