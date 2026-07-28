package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountResolverSpecialistConfigurationTest {

    private final AccountResolutionService accountResolutionService =
        mock(AccountResolutionService.class);
    private final SpecialistDefinition<
        AccountResolutionRequest,
        AccountResolutionResult
    > definition;

    AccountResolverSpecialistConfigurationTest() {
        when(accountResolutionService.policies()).thenReturn(policies());
        definition = new AccountResolverSpecialistConfiguration()
            .accountResolverSpecialist(
                new ObjectMapper().findAndRegisterModules(),
                accountResolutionService
            );
    }

    @Test
    void declaresOneReadActionOneVectorSpaceAndIterativeMode() {
        assertThat(definition.id().toString()).isEqualTo("account-resolver@1");
        assertThat(definition.executionProfile().mode()).isEqualTo("resolver");
        assertThat(definition.executionProfile().strategy())
            .isEqualTo(ExecutionStrategy.BOUNDED_ITERATIVE);
        assertThat(definition.executionProfile().writeEnabled()).isFalse();
        assertThat(definition.executionProfile()
            .requestedCapabilities().visibleActions())
            .containsExactly("get_account_profile");
        assertThat(definition.executionProfile()
            .requestedCapabilities().requestableReadActions())
            .containsExactly("get_account_profile");
        assertThat(definition.executionProfile()
            .requestedCapabilities().proposableWriteActions())
            .isEmpty();
        assertThat(definition.executionProfile()
            .requestedCapabilities().requestedVectorSpaces())
            .containsExactly("account-resolution-policy");
    }

    @Test
    void keepsOnlyUserQuestionAsConversationInput() {
        AccountResolutionRequest input = new AccountResolutionRequest(
            "Why is my account blocked?"
        );

        assertThat(definition.inputAdapter().conversationInput(input))
            .isEqualTo("Why is my account blocked?");
        assertThat(definition.inputAdapter().renderModelInput(input))
            .contains("Why is my account blocked?")
            .contains("evidence-grounded account readiness diagnosis")
            .contains("retrieved account policy evidence")
            .contains("information diagnosis")
            .doesNotContain("userId", "subscriptionId", "tenantId");
    }

    @Test
    void requiresPolicyEvidenceBeforeProducingAccountReadiness() {
        OrchestrationResult result = accountProfileResult(
            true,
            true,
            true,
            true,
            true
        );

        assertThatThrownBy(() ->
            definition.outputAdapter().validateGrounding(result, List.of())
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires complete policy evidence");

        definition.outputAdapter().validateGrounding(
            result,
            policyEvidence()
        );
    }

    @Test
    void rejectsPartialReadinessPolicyEvidence() {
        OrchestrationResult result = accountProfileResult(
            true,
            false,
            false,
            true,
            true
        );

        assertThatThrownBy(() ->
            definition.outputAdapter().validateGrounding(
                result,
                List.of(policyEvidence().get(1))
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ACTIVE_ACCOUNT_REQUIRED")
            .hasMessageContaining("BILLING_ADDRESS_REQUIRED");
    }

    @Test
    void requiresAuthoritativeProfileFactsBeforeProducingAccountReadiness() {
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("Account profile loaded.")
            .build();

        assertThatThrownBy(() ->
            definition.outputAdapter().validateGrounding(
                result,
                policyEvidence()
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "Authoritative account profile facts are required"
            );
    }

    @Test
    void projectsAndValidatesBlockedStructuredOutput() {
        OrchestrationResult orchestrationResult = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("""
                {
                  "assessment": "BLOCKED",
                  "summary": "A verified payment method is missing.",
                  "blockers": [{
                    "requirement": "VERIFIED_PAYMENT_METHOD",
                    "explanation": "The account has no verified payment method.",
                    "recommendedNextStep": "Add and verify a payment method."
                  }]
                }
                """)
            .build();

        AccountResolutionResult output = definition.outputAdapter().project(
            orchestrationResult,
            List.of(new AIEvidenceReference(
                "policy-1",
                "Verified payment is required.",
                0.98,
                "policy",
                null,
                "account-resolution-policy",
                Map.of("entityId", "PAYMENT_REQUIRED")
            ))
        );
        definition.outputAdapter().validate(output);

        assertThat(output.assessment())
            .isEqualTo(AccountResolutionResult.Assessment.BLOCKED);
        assertThat(output.blockers()).singleElement().satisfies(blocker ->
            assertThat(blocker.requirement()).isEqualTo(
                AccountResolutionResult.Requirement.VERIFIED_PAYMENT_METHOD
            )
        );
    }

    @Test
    void projectsStructuredOutputFromCompoundChild() {
        OrchestrationResult profileRead = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("Account profile loaded.")
            .build();
        OrchestrationResult generatedAssessment = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("""
                {
                  "assessment": "READY",
                  "summary": "The account satisfies the current requirements.",
                  "blockers": []
                }
                """)
            .build();
        OrchestrationResult compound = OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(true)
            .message("All intents processed successfully.")
            .children(List.of(profileRead, generatedAssessment))
            .build();

        AccountResolutionResult output = definition.outputAdapter().project(
            compound,
            List.of()
        );

        assertThat(output.assessment())
            .isEqualTo(AccountResolutionResult.Assessment.READY);
    }

    @Test
    void rejectsConflictingStructuredOutputsFromCompoundChildren() {
        OrchestrationResult ready = OrchestrationResult.builder()
            .success(true)
            .message("""
                {"assessment":"READY","summary":"Ready.","blockers":[]}
                """)
            .build();
        OrchestrationResult insufficient = OrchestrationResult.builder()
            .success(true)
            .message("""
                {"assessment":"INSUFFICIENT_EVIDENCE","summary":"Unknown.","blockers":[]}
                """)
            .build();
        OrchestrationResult compound = OrchestrationResult.builder()
            .success(true)
            .children(List.of(ready, insufficient))
            .build();

        assertThatThrownBy(() ->
            definition.outputAdapter().project(compound, List.of())
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conflicting JSON outputs");
    }

    @Test
    void rejectsContradictoryOrUnstructuredOutput() {
        AccountResolutionResult contradictory = new AccountResolutionResult(
            AccountResolutionResult.Assessment.READY,
            "Ready",
            List.of(new AccountResolutionResult.Blocker(
                AccountResolutionResult.Requirement.OTHER,
                "Unexpected",
                "Do something"
            ))
        );

        assertThatThrownBy(() ->
            definition.outputAdapter().validate(contradictory)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("READY assessment requires no blockers");

        OrchestrationResult prose = OrchestrationResult.builder()
            .success(true)
            .message("The account appears ready.")
            .build();
        assertThatThrownBy(() ->
            definition.outputAdapter().project(prose, List.of())
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("complete JSON");
    }

    @Test
    void acceptsOnlyTheBlockersProvedByAuthoritativeProfileFacts() {
        AccountResolutionResult output = new AccountResolutionResult(
            AccountResolutionResult.Assessment.BLOCKED,
            "A verified payment method is required.",
            List.of(blocker(
                AccountResolutionResult.Requirement.VERIFIED_PAYMENT_METHOD
            ))
        );

        definition.outputAdapter().validateFinalOutput(
            output,
            accountProfileResult(true, false, false, true, true),
            policyEvidence()
        );
    }

    @Test
    void rejectsPlausiblePolicyBlockersThatAreNotMissingFromProfile() {
        AccountResolutionResult output = new AccountResolutionResult(
            AccountResolutionResult.Assessment.BLOCKED,
            "Several account requirements need attention.",
            List.of(
                blocker(AccountResolutionResult.Requirement.ACTIVE_SUBSCRIPTION),
                blocker(
                    AccountResolutionResult.Requirement.VERIFIED_PAYMENT_METHOD
                ),
                blocker(
                    AccountResolutionResult.Requirement
                        .VALIDATED_BILLING_ADDRESS
                )
            )
        );

        assertThatThrownBy(() ->
            definition.outputAdapter().validateFinalOutput(
                output,
                accountProfileResult(true, false, false, true, true),
                policyEvidence()
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "blockers conflict with authoritative account profile facts"
            );
    }

    @Test
    void acceptsReadyOnlyWhenEveryAuthoritativeRequirementIsSatisfied() {
        AccountResolutionResult output = new AccountResolutionResult(
            AccountResolutionResult.Assessment.READY,
            "The account satisfies the current requirements.",
            List.of()
        );

        definition.outputAdapter().validateFinalOutput(
            output,
            accountProfileResult(true, true, true, true, true),
            policyEvidence()
        );
    }

    @Test
    void canonicalizesHostileModelProseFromApplicationOwnedPolicies() {
        AccountResolutionResult providerOutput = new AccountResolutionResult(
            AccountResolutionResult.Assessment.BLOCKED,
            "Payment is required before canceling the subscription.",
            List.of(new AccountResolutionResult.Blocker(
                AccountResolutionResult.Requirement.VERIFIED_PAYMENT_METHOD,
                "Canceling requires payment.",
                "Pay before cancellation."
            ))
        );
        OrchestrationResult source = accountProfileResult(
            true,
            false,
            false,
            true,
            true
        );

        definition.outputAdapter().validateFinalOutput(
            providerOutput,
            source,
            policyEvidence()
        );
        AccountResolutionResult normalized =
            definition.outputAdapter().normalizeFinalOutput(
                providerOutput,
                source,
                policyEvidence()
            );
        definition.outputAdapter().validateFinalOutput(
            normalized,
            source,
            policyEvidence()
        );

        assertThat(normalized.summary())
            .isEqualTo("Your account has one unmet policy requirement.")
            .doesNotContainIgnoringCase("cancel");
        assertThat(normalized.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.explanation())
                .isEqualTo(
                    "A missing or unverified payment method blocks ordering and paid feature usage until the user confirms a replacement method."
                )
                .doesNotContainIgnoringCase("cancel");
            assertThat(blocker.recommendedNextStep())
                .isEqualTo(
                    "Add and verify a payment method before ordering or continuing paid usage."
                );
        });
    }

    private OrchestrationResult accountProfileResult(
        boolean subscriptionActive,
        boolean paymentMethodPresent,
        boolean paymentMethodVerified,
        boolean billingAddressPresent,
        boolean billingAddressValidated
    ) {
        String evidenceSummary = """
            {
              "subscriptionActive": %s,
              "paymentMethodPresent": %s,
              "paymentMethodVerified": %s,
              "billingAddressPresent": %s,
              "billingAddressValidated": %s
            }
            """.formatted(
                subscriptionActive,
                paymentMethodPresent,
                paymentMethodVerified,
                billingAddressPresent,
                billingAddressValidated
            );
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("Account profile loaded.")
            .data(Map.of(
                "readActionResolution",
                Map.of(
                    "executedActions",
                    List.of(Map.of(
                        "action",
                        "get_account_profile",
                        "groundingUsable",
                        true,
                        "evidenceSummary",
                        evidenceSummary
                    ))
                )
            ))
            .build();
    }

    private List<AIEvidenceReference> policyEvidence() {
        return List.of(
            policyEvidence(
                "ACTIVE_ACCOUNT_REQUIRED",
                "An active subscription is required."
            ),
            policyEvidence(
                "PAYMENT_METHOD_REQUIRED",
                "A verified payment method is required."
            ),
            policyEvidence(
                "BILLING_ADDRESS_REQUIRED",
                "A validated billing address is required."
            )
        );
    }

    private AIEvidenceReference policyEvidence(String id, String content) {
        return new AIEvidenceReference(
            id,
            content,
            0.98,
            "policy",
            null,
            "account-resolution-policy",
            Map.of()
        );
    }

    private List<AccountResolutionService.ResolutionPolicy> policies() {
        return List.of(
            new AccountResolutionService.ResolutionPolicy(
                "ACTIVE_ACCOUNT_REQUIRED",
                "Active subscription required",
                "The account must have an active subscription before product ordering or app usage can continue.",
                "subscribe",
                true
            ),
            new AccountResolutionService.ResolutionPolicy(
                "PAYMENT_METHOD_REQUIRED",
                "Verified payment method required",
                "A missing or unverified payment method blocks ordering and paid feature usage until the user confirms a replacement method.",
                "update_payment_method",
                true
            ),
            new AccountResolutionService.ResolutionPolicy(
                "BILLING_ADDRESS_REQUIRED",
                "Validated billing address required",
                "A missing or unvalidated billing address blocks ordering until the address is supplied and confirmed.",
                "update_address",
                true
            )
        );
    }

    private AccountResolutionResult.Blocker blocker(
        AccountResolutionResult.Requirement requirement
    ) {
        return new AccountResolutionResult.Blocker(
            requirement,
            "The requirement is not satisfied.",
            "Resolve the missing requirement."
        );
    }
}
