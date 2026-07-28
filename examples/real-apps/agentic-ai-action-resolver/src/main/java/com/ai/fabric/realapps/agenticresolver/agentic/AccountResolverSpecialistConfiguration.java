package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountResolverSpecialistConfiguration {

    public static final SpecialistId SPECIALIST_ID =
        SpecialistId.of("account-resolver", "1");
    public static final SpecialistId READ_SPECIALIST_ID =
        SpecialistId.of("account-resolver-read", "1");
    public static final String PROFILE_ACTION = "get_account_profile";
    public static final String UPDATE_ADDRESS_ACTION = "update_address";
    public static final String POLICY_VECTOR_SPACE = "account-resolution-policy";

    @Bean
    SpecialistDefinition<AccountResolutionRequest, AccountResolutionResult>
        accountResolverSpecialist(
            ObjectMapper objectMapper,
            AccountResolutionService accountResolutionService
        ) {
        return specialistDefinition(
            SPECIALIST_ID,
            "Agentic Account Resolver",
            true,
            objectMapper,
            accountResolutionService
        );
    }

    @Bean
    SpecialistDefinition<AccountResolutionRequest, AccountResolutionResult>
        accountResolverReadSpecialist(
            ObjectMapper objectMapper,
            AccountResolutionService accountResolutionService
        ) {
        return specialistDefinition(
            READ_SPECIALIST_ID,
            "Account Readiness Evaluator",
            false,
            objectMapper,
            accountResolutionService
        );
    }

    private SpecialistDefinition<
        AccountResolutionRequest,
        AccountResolutionResult
    > specialistDefinition(
        SpecialistId specialistId,
        String name,
        boolean writeEnabled,
        ObjectMapper objectMapper,
        AccountResolutionService accountResolutionService
    ) {
        Set<String> visibleActions = writeEnabled
            ? Set.of(PROFILE_ACTION, UPDATE_ADDRESS_ACTION)
            : Set.of(PROFILE_ACTION);
        RequestedCapabilityProfile capabilities = new RequestedCapabilityProfile(
            true,
            Set.of(POLICY_VECTOR_SPACE),
            visibleActions,
            Set.of(PROFILE_ACTION),
            writeEnabled ? Set.of(UPDATE_ADDRESS_ACTION) : Set.of()
        );
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                specialistId,
                name,
                "Evaluates the current account against approved policy evidence"
            ),
            new SpecialistInstructions(
                "Determine whether the current account can continue and explain any blocker.",
                writeEnabled
                    ? """
                Read the current account only through get_account_profile.
                Retrieve only account-resolution-policy evidence.
                Treat profile facts as current state and policy evidence as requirements.
                Never use an account, user, subscription, tenant, or scope supplied in the question.
                You may propose update_address only when the user explicitly asks to
                update the current account address and supplies its required address
                fields. Never invent a missing address field.
                A proposal is not authorization or execution. The application owns
                confirmation and executes only a durable confirmed receipt.
                Never claim that a proposed write already happened.
                If either current profile facts or relevant policy evidence is insufficient,
                return INSUFFICIENT_EVIDENCE instead of guessing.
                """
                    : """
                Read the current account only through get_account_profile.
                Retrieve only account-resolution-policy evidence.
                Treat profile facts as current state and policy evidence as requirements.
                Never use an account, user, subscription, tenant, or scope supplied in the question.
                This specialist is read-only. Do not propose or claim any write.
                If either current profile facts or relevant policy evidence is insufficient,
                return INSUFFICIENT_EVIDENCE instead of guessing.
                """
            ),
            new SpecialistExecutionProfile(
                "resolver",
                capabilities,
                ExecutionStrategy.BOUNDED_ITERATIVE,
                writeEnabled
            ),
            new SpecialistLimits(
                Duration.ofSeconds(45),
                10_000,
                16_000,
                8
            ),
            inputAdapter(writeEnabled),
            outputAdapter(
                objectMapper,
                readinessPolicies(accountResolutionService.policies())
            )
        );
    }

    private SpecialistInputAdapter<AccountResolutionRequest> inputAdapter(
        boolean writeEnabled
    ) {
        return new SpecialistInputAdapter<>() {
            @Override
            public Class<AccountResolutionRequest> inputType() {
                return AccountResolutionRequest.class;
            }

            @Override
            public void validate(AccountResolutionRequest input) {
                if (input == null
                    || input.question() == null
                    || input.question().isBlank()) {
                    throw new IllegalArgumentException("question is required");
                }
                if (input.question().length() > 2_000) {
                    throw new IllegalArgumentException(
                        "question must not exceed 2000 characters"
                    );
                }
            }

            @Override
            public String renderModelInput(AccountResolutionRequest input) {
                String task = writeEnabled
                    ? """
                    Resolve the application question for the current trusted account.
                    Follow the registered specialist contract and use only its effective
                    capabilities and approved evidence.
                    Application question:
                    %s
                    """
                    : """
                    Evaluate the application question for the current trusted account.
                    Follow the registered read-only specialist contract and use only its
                    effective capabilities and approved evidence.
                    Application question:
                    %s
                    """;
                return task.formatted(input.question()).trim();
            }

            @Override
            public String conversationInput(AccountResolutionRequest input) {
                return input.question();
            }

            @Override
            public OrchestrationContext orchestrationContext(
                AccountResolutionRequest input
            ) {
                return OrchestrationContext.builder()
                    .position("resolver")
                    .build();
            }
        };
    }

    private SpecialistOutputAdapter<AccountResolutionResult> outputAdapter(
        ObjectMapper objectMapper,
        Map<
            AccountResolutionResult.Requirement,
            AccountResolutionService.ResolutionPolicy
        > readinessPolicies
    ) {
        return new SpecialistOutputAdapter<>() {
            @Override
            public Class<AccountResolutionResult> outputType() {
                return AccountResolutionResult.class;
            }

            @Override
            public SpecialistOutputMode outputMode() {
                return SpecialistOutputMode.DIRECT_PROJECTION;
            }

            @Override
            public void validateGrounding(
                OrchestrationResult result,
                List<AIEvidenceReference> evidence
            ) {
                Set<String> policyEvidenceIds = new LinkedHashSet<>();
                if (evidence != null) {
                    evidence.stream()
                        .filter(reference ->
                            reference != null
                                && POLICY_VECTOR_SPACE.equals(
                                    reference.vectorSpace()
                                )
                        )
                        .map(AIEvidenceReference::evidenceId)
                        .forEach(policyEvidenceIds::add);
                }
                Set<String> missingPolicyEvidence = readinessPolicies.values()
                    .stream()
                    .map(AccountResolutionService.ResolutionPolicy::code)
                    .filter(code -> !policyEvidenceIds.contains(code))
                    .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new
                    ));
                if (!missingPolicyEvidence.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Account readiness requires complete policy evidence; missing: "
                            + String.join(", ", missingPolicyEvidence)
                    );
                }
                authoritativeProfileFacts(result);
            }

            @Override
            public AccountResolutionResult project(
                OrchestrationResult result,
                List<AIEvidenceReference> evidence
            ) {
                return canonicalOutput(result);
            }

            @Override
            public void validate(AccountResolutionResult output) {
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
                if (output.assessment()
                        == AccountResolutionResult.Assessment.BLOCKED
                    && output.blockers().isEmpty()) {
                    throw new IllegalArgumentException(
                        "BLOCKED assessment requires at least one blocker"
                    );
                }
                if (output.assessment()
                        != AccountResolutionResult.Assessment.BLOCKED
                    && !output.blockers().isEmpty()) {
                    throw new IllegalArgumentException(
                        output.assessment() + " assessment requires no blockers"
                    );
                }
            }

            @Override
            public AccountResolutionResult normalizeFinalOutput(
                AccountResolutionResult output,
                OrchestrationResult sourceResult,
                List<AIEvidenceReference> evidence
            ) {
                return canonicalOutput(sourceResult);
            }

            private AccountResolutionResult canonicalOutput(
                OrchestrationResult sourceResult
            ) {
                Map<String, Object> facts =
                    authoritativeProfileFacts(sourceResult);
                List<AccountResolutionResult.Blocker> canonicalBlockers =
                    expectedRequirements(facts).stream()
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
                AccountResolutionResult.Assessment assessment =
                    canonicalBlockers.isEmpty()
                        ? AccountResolutionResult.Assessment.READY
                        : AccountResolutionResult.Assessment.BLOCKED;
                return new AccountResolutionResult(
                    assessment,
                    canonicalSummary(assessment, canonicalBlockers),
                    canonicalBlockers
                );
            }

            @Override
            public void validateFinalOutput(
                AccountResolutionResult output,
                OrchestrationResult sourceResult,
                List<AIEvidenceReference> evidence
            ) {
                validate(output);
                Map<String, Object> facts =
                    authoritativeProfileFacts(sourceResult);
                Set<AccountResolutionResult.Requirement> expected =
                    expectedRequirements(facts);

                Set<AccountResolutionResult.Requirement> actual =
                    output.blockers().stream()
                        .map(AccountResolutionResult.Blocker::requirement)
                        .collect(java.util.stream.Collectors.toCollection(
                            LinkedHashSet::new
                        ));
                AccountResolutionResult.Assessment expectedAssessment =
                    expected.isEmpty()
                        ? AccountResolutionResult.Assessment.READY
                        : AccountResolutionResult.Assessment.BLOCKED;
                if (output.assessment() != expectedAssessment) {
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

            private Set<AccountResolutionResult.Requirement>
                expectedRequirements(Map<String, Object> facts) {
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
                        AccountResolutionResult.Requirement
                            .VERIFIED_PAYMENT_METHOD
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

            @Override
            public String conversationOutput(
                AccountResolutionResult output,
                OrchestrationResult sourceResult
            ) {
                StringBuilder conversation = new StringBuilder(output.summary());
                for (AccountResolutionResult.Blocker blocker : output.blockers()) {
                    conversation.append("\nBlocker: ")
                        .append(blocker.requirement())
                        .append(". ")
                        .append(blocker.explanation())
                        .append(" Next step: ")
                        .append(blocker.recommendedNextStep());
                }
                return conversation.toString();
            }

            private Map<String, Object> authoritativeProfileFacts(
                OrchestrationResult result
            ) {
                if (result == null || result.getData() == null) {
                    throw new IllegalArgumentException(
                        "Authoritative account profile facts are required"
                    );
                }
                Object rawResolution = result.getData().get(
                    "readActionResolution"
                );
                if (!(rawResolution instanceof Map<?, ?> resolution)) {
                    throw new IllegalArgumentException(
                        "Authoritative account profile facts are required"
                    );
                }
                Object rawActions = resolution.get("executedActions");
                if (!(rawActions instanceof List<?> actions)) {
                    throw new IllegalArgumentException(
                        "Authoritative account profile facts are required"
                    );
                }
                for (Object rawAction : actions) {
                    if (!(rawAction instanceof Map<?, ?> action)
                        || !PROFILE_ACTION.equals(action.get("action"))
                        || !Boolean.TRUE.equals(
                            action.get("groundingUsable")
                        )) {
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

            private boolean requiredBoolean(
                Map<String, Object> facts,
                String name
            ) {
                Object value = facts != null ? facts.get(name) : null;
                if (!(value instanceof Boolean booleanValue)) {
                    throw new IllegalArgumentException(
                        "Authoritative account profile fact is missing: " + name
                    );
                }
                return booleanValue;
            }
        };
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
            for (AccountResolutionService.ResolutionPolicy policy : policies) {
                if (policy != null) {
                    byCode.put(policy.code(), policy);
                }
            }
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
            AccountResolutionResult.Requirement.VALIDATED_BILLING_ADDRESS,
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
