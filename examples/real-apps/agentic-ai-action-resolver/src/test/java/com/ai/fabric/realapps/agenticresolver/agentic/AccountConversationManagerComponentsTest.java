package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AccountConversationManagerComponentsTest {

    private static final Instant NOW =
        Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void exposesCurrentTurnBillingCompletenessWithoutInventingValues() {
        AccountConversationManagerInputAdapter adapter =
            new AccountConversationManagerInputAdapter();

        Map<String, String> incomplete = contextMap(
            adapter.applicationContext(
                new AccountDelegationCoordinatorRequest(
                    "Assess this refund.",
                    RefundRequest.ResolutionType.REFUND,
                    null
                )
            )
        );
        Map<String, String> complete = contextMap(
            adapter.applicationContext(
                new AccountDelegationCoordinatorRequest(
                    "Assess this credit.",
                    RefundRequest.ResolutionType.ACCOUNT_CREDIT,
                    new BigDecimal("25.00")
                )
            )
        );

        assertThat(incomplete)
            .containsEntry("billingInputState", "AMOUNT_MISSING")
            .containsEntry("resolutionType", "REFUND")
            .doesNotContainKey("amount");
        assertThat(complete)
            .containsEntry("billingInputState", "COMPLETE")
            .containsEntry("resolutionType", "ACCOUNT_CREDIT")
            .containsEntry("amount", "25");

        Map<String, String> missingType = contextMap(
            adapter.applicationContext(
                new AccountDelegationCoordinatorRequest(
                    "Assess this billing adjustment.",
                    null,
                    new BigDecimal("75")
                )
            )
        );
        assertThat(missingType)
            .containsEntry(
                "billingInputState",
                "RESOLUTION_TYPE_MISSING"
            )
            .containsEntry("amount", "75")
            .doesNotContainKey("resolutionType");
    }

    @Test
    void projectsAccountSummaryAndFirstRecommendedStep() {
        AccountResolutionResult output = new AccountResolutionResult(
            AccountResolutionResult.Assessment.BLOCKED,
            "Your account is blocked by an unverified payment method.",
            List.of(new AccountResolutionResult.Blocker(
                AccountResolutionResult.Requirement
                    .VERIFIED_PAYMENT_METHOD,
                "A verified method is required.",
                "Add and confirm a payment method."
            ))
        );

        String projected = new AccountManagerReadResultProjector().project(
            new AccountDelegationCoordinatorRequest(
                "Why am I blocked?",
                null,
                null
            ),
            success(
                AccountResolverSpecialists.READ_SPECIALIST_ID,
                output
            )
        );

        assertThat(projected)
            .contains("unverified payment method")
            .contains(
                "Recommended next step: Add and confirm a payment method."
            );
    }

    @Test
    void mapsManagerSelectedAccountReadToItsNarrowTrustedTask() {
        AccountResolutionRequest mapped =
            new AccountManagerReadInputMapper().map(
                new AccountDelegationCoordinatorRequest(
                    "Which requirement should I resolve first?",
                    null,
                    null
                )
            );

        assertThat(mapped.question())
            .contains("current backend-owned account profile")
            .contains("readiness and blockers")
            .doesNotContain("Which requirement");
    }

    @Test
    void billingMapperRequiresCompleteFactsAndProjectorExplainsPolicyPath() {
        BillingManagerInputMapper mapper = new BillingManagerInputMapper();

        assertThatThrownBy(() -> mapper.map(
            new AccountDelegationCoordinatorRequest(
                "Assess a refund.",
                RefundRequest.ResolutionType.REFUND,
                null
            )
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Complete billing facts");

        BillingResolutionAssessmentResult output =
            new BillingResolutionAssessmentResult(
                RefundRequest.ResolutionType.ACCOUNT_CREDIT,
                new BigDecimal("25.00"),
                BillingResolutionAssessmentResult.Decision.AUTO_APPROVED,
                BillingResolutionAssessmentResult.ExpectedStatus.APPROVED,
                new BigDecimal("50"),
                "The amount is within the automatic account-credit limit."
            );
        String projected = new BillingManagerResultProjector().project(
            new AccountDelegationCoordinatorRequest(
                "Assess this credit.",
                RefundRequest.ResolutionType.ACCOUNT_CREDIT,
                new BigDecimal("25")
            ),
            success(
                AccountResolverSpecialists.MANAGER_BILLING_ADVISOR_ID,
                output
            )
        );

        assertThat(projected)
            .contains("ACCOUNT_CREDIT of 25")
            .contains("AUTO_APPROVED")
            .contains("expected status APPROVED")
            .contains("automatic account-credit limit");
    }

    private <O> AIExecutionResult<O> success(
        ai.fabric.execution.specialist.SpecialistId specialistId,
        O output
    ) {
        return new AIExecutionResult<>(
            "invocation-1",
            specialistId,
            AIExecutionStatus.SUCCEEDED,
            output,
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW
        );
    }

    private Map<String, String> contextMap(
        List<ConversationManagerContextValue> values
    ) {
        return values.stream().collect(Collectors.toMap(
            ConversationManagerContextValue::name,
            ConversationManagerContextValue::value
        ));
    }
}
