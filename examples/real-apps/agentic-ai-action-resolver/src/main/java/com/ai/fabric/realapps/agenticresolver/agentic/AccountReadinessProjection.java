package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.intent.orchestration.OrchestrationResult;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AccountReadinessProjection {

    private final ObjectMapper objectMapper;
    private final Map<
        AccountResolutionResult.Requirement,
        AccountResolutionService.ResolutionPolicy
    > readinessPolicies;

    AccountReadinessProjection(
        ObjectMapper objectMapper,
        AccountResolutionService accountResolutionService
    ) {
        this.objectMapper = objectMapper;
        this.readinessPolicies = readinessPolicies(
            accountResolutionService.policies()
        );
    }

    void validateGrounding(OrchestrationResult result) {
        authoritativeProfileFacts(result);
    }

    JsonNode project(OrchestrationResult result) {
        return objectMapper.valueToTree(canonicalOutput(result));
    }

    void validateFinalOutput(
        JsonNode output,
        OrchestrationResult sourceResult
    ) {
        AccountResolutionResult typed;
        try {
            typed = objectMapper.treeToValue(
                output,
                AccountResolutionResult.class
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(
                "Account readiness output is invalid",
                ex
            );
        }
        validateStructure(typed);
        Set<AccountResolutionResult.Requirement> expected =
            expectedRequirements(authoritativeProfileFacts(sourceResult));
        Set<AccountResolutionResult.Requirement> actual =
            typed.blockers().stream()
                .map(AccountResolutionResult.Blocker::requirement)
                .collect(java.util.stream.Collectors.toCollection(
                    LinkedHashSet::new
                ));
        AccountResolutionResult.Assessment expectedAssessment =
            expected.isEmpty()
                ? AccountResolutionResult.Assessment.READY
                : AccountResolutionResult.Assessment.BLOCKED;
        if (typed.assessment() != expectedAssessment) {
            throw new IllegalArgumentException(
                "assessment conflicts with authoritative account profile facts"
            );
        }
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                "blockers conflict with authoritative account profile facts"
            );
        }
    }

    private AccountResolutionResult canonicalOutput(
        OrchestrationResult sourceResult
    ) {
        Set<AccountResolutionResult.Requirement> requirements =
            expectedRequirements(authoritativeProfileFacts(sourceResult));
        List<AccountResolutionResult.Blocker> blockers = requirements.stream()
            .map(requirement -> {
                AccountResolutionService.ResolutionPolicy policy =
                    readinessPolicies.get(requirement);
                return new AccountResolutionResult.Blocker(
                    requirement,
                    policy.description(),
                    recommendedNextStep(requirement)
                );
            })
            .toList();
        AccountResolutionResult.Assessment assessment = blockers.isEmpty()
            ? AccountResolutionResult.Assessment.READY
            : AccountResolutionResult.Assessment.BLOCKED;
        return new AccountResolutionResult(
            assessment,
            canonicalSummary(assessment, blockers),
            blockers
        );
    }

    private void validateStructure(AccountResolutionResult output) {
        if (output == null || output.assessment() == null) {
            throw new IllegalArgumentException("assessment is required");
        }
        if (output.summary() == null || output.summary().isBlank()) {
            throw new IllegalArgumentException("summary is required");
        }
        for (AccountResolutionResult.Blocker blocker : output.blockers()) {
            if (blocker == null || blocker.requirement() == null) {
                throw new IllegalArgumentException(
                    "blocker requirement is required"
                );
            }
            if (blocker.explanation() == null
                || blocker.explanation().isBlank()) {
                throw new IllegalArgumentException(
                    "blocker explanation is required"
                );
            }
            if (blocker.recommendedNextStep() == null
                || blocker.recommendedNextStep().isBlank()) {
                throw new IllegalArgumentException(
                    "blocker recommendedNextStep is required"
                );
            }
        }
        if (output.assessment() == AccountResolutionResult.Assessment.BLOCKED
            && output.blockers().isEmpty()) {
            throw new IllegalArgumentException(
                "BLOCKED assessment requires at least one blocker"
            );
        }
        if (output.assessment() != AccountResolutionResult.Assessment.BLOCKED
            && !output.blockers().isEmpty()) {
            throw new IllegalArgumentException(
                output.assessment() + " assessment requires no blockers"
            );
        }
    }

    private Set<AccountResolutionResult.Requirement> expectedRequirements(
        Map<String, Object> facts
    ) {
        Set<AccountResolutionResult.Requirement> expected =
            new LinkedHashSet<>();
        if (!requiredBoolean(facts, "subscriptionActive")) {
            expected.add(
                AccountResolutionResult.Requirement.ACTIVE_SUBSCRIPTION
            );
        }
        if (!requiredBoolean(facts, "paymentMethodPresent")
            || !requiredBoolean(facts, "paymentMethodVerified")) {
            expected.add(
                AccountResolutionResult.Requirement.VERIFIED_PAYMENT_METHOD
            );
        }
        if (!requiredBoolean(facts, "billingAddressPresent")
            || !requiredBoolean(facts, "billingAddressValidated")) {
            expected.add(
                AccountResolutionResult.Requirement
                    .VALIDATED_BILLING_ADDRESS
            );
        }
        return expected;
    }

    private String canonicalSummary(
        AccountResolutionResult.Assessment assessment,
        List<AccountResolutionResult.Blocker> blockers
    ) {
        return switch (assessment) {
            case READY ->
                "Your account currently satisfies the evaluated account-readiness policies.";
            case BLOCKED -> blockers.size() == 1
                ? "Your account has one unmet policy requirement."
                : "Your account has %d unmet policy requirements."
                    .formatted(blockers.size());
            case INSUFFICIENT_EVIDENCE ->
                "There is not enough approved evidence to determine account readiness.";
        };
    }

    private String recommendedNextStep(
        AccountResolutionResult.Requirement requirement
    ) {
        return switch (requirement) {
            case ACTIVE_SUBSCRIPTION ->
                "Activate a subscription before ordering or continuing account usage.";
            case VERIFIED_PAYMENT_METHOD ->
                "Add and verify a payment method before ordering or continuing paid usage.";
            case VALIDATED_BILLING_ADDRESS ->
                "Provide and validate a billing address before placing an order.";
            case OTHER -> throw new IllegalArgumentException(
                "Unsupported account-readiness requirement: OTHER"
            );
        };
    }

    private Map<String, Object> authoritativeProfileFacts(
        OrchestrationResult result
    ) {
        if (result == null || result.getData() == null) {
            throw new IllegalArgumentException(
                "Authoritative account profile facts are required"
            );
        }
        Object rawResolution = result.getData().get("readActionResolution");
        if (!(rawResolution instanceof Map<?, ?> resolution)
            || !(resolution.get("executedActions") instanceof List<?> actions)) {
            throw new IllegalArgumentException(
                "Authoritative account profile facts are required"
            );
        }
        for (Object rawAction : actions) {
            if (!(rawAction instanceof Map<?, ?> action)
                || !AccountResolverSpecialists.PROFILE_ACTION.equals(
                    action.get("action")
                )
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
                requiredBoolean(facts, "subscriptionActive");
                requiredBoolean(facts, "paymentMethodPresent");
                requiredBoolean(facts, "paymentMethodVerified");
                requiredBoolean(facts, "billingAddressPresent");
                requiredBoolean(facts, "billingAddressValidated");
                return facts;
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException(
                    "Authoritative account profile facts are invalid",
                    ex
                );
            }
        }
        throw new IllegalArgumentException(
            "Authoritative account profile facts are required"
        );
    }

    private boolean requiredBoolean(Map<String, Object> facts, String name) {
        Object value = facts != null ? facts.get(name) : null;
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(
                "Authoritative account profile fact is missing: " + name
            );
        }
        return booleanValue;
    }

    private Map<
        AccountResolutionResult.Requirement,
        AccountResolutionService.ResolutionPolicy
    > readinessPolicies(
        List<AccountResolutionService.ResolutionPolicy> policies
    ) {
        Map<String, AccountResolutionService.ResolutionPolicy> byCode =
            new LinkedHashMap<>();
        if (policies != null) {
            policies.stream()
                .filter(java.util.Objects::nonNull)
                .forEach(policy -> byCode.put(policy.code(), policy));
        }
        Map<
            AccountResolutionResult.Requirement,
            AccountResolutionService.ResolutionPolicy
        > required = new LinkedHashMap<>();
        required.put(
            AccountResolutionResult.Requirement.ACTIVE_SUBSCRIPTION,
            requirePolicy(byCode, "ACTIVE_ACCOUNT_REQUIRED")
        );
        required.put(
            AccountResolutionResult.Requirement.VERIFIED_PAYMENT_METHOD,
            requirePolicy(byCode, "PAYMENT_METHOD_REQUIRED")
        );
        required.put(
            AccountResolutionResult.Requirement
                .VALIDATED_BILLING_ADDRESS,
            requirePolicy(byCode, "BILLING_ADDRESS_REQUIRED")
        );
        return Map.copyOf(required);
    }

    private AccountResolutionService.ResolutionPolicy requirePolicy(
        Map<String, AccountResolutionService.ResolutionPolicy> policies,
        String code
    ) {
        AccountResolutionService.ResolutionPolicy policy = policies.get(code);
        if (policy == null) {
            throw new IllegalStateException(
                "Required account-resolution policy is missing: " + code
            );
        }
        return policy;
    }
}
