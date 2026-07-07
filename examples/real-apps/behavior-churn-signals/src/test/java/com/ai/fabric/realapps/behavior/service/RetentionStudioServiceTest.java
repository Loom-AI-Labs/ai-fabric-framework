package com.ai.fabric.realapps.behavior.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionStudioServiceTest {

    private final RetentionStudioService service = new RetentionStudioService();

    @Test
    void behaviorSeedProducesDeterministicRiskAndEvidence() {
        RetentionStudioService.RetentionReviewResult result = service.review(new RetentionStudioService.RetentionReviewRequest(
            "acct-1001",
            "user-2001",
            "pro",
            65,
            2,
            3,
            aiEvidence("RETENTION_OFFER", 0.91, "CHURNING", "RAPIDLY_DECLINING",
                List.of("Offer retention credit", "Assign CSM outreach"))
        ));

        assertThat(result.riskCategory()).isEqualTo("HIGH");
        assertThat(result.actionFamily()).isEqualTo("RETENTION_OFFER");
        assertThat(result.evidenceIds()).contains("insight-acct-1001-user-2001", "plan-pro", "ai-action-retention_offer");
        assertThat(result.recommendation()).contains("Offer retention credit");
        assertThat(result.policyExplanation()).contains("LLM-selected action family RETENTION_OFFER");
        assertThat(result.policyExplanation()).contains("does not execute a customer-facing offer");
    }

    @Test
    void retentionOfferRequiresConfirmation() {
        RetentionStudioService.RetentionOfferResult gated = service.createOffer(
            new RetentionStudioService.RetentionOfferRequest("acct-1001", "user-2001", 20, false)
        );

        assertThat(gated.success()).isFalse();
        assertThat(gated.confirmationRequired()).isTrue();

        RetentionStudioService.RetentionOfferResult executed = service.createOffer(
            new RetentionStudioService.RetentionOfferRequest("acct-1001", "user-2001", 20, true)
        );

        assertThat(executed.success()).isTrue();
        assertThat(executed.data()).containsEntry("accountId", "acct-1001");
        assertThat(executed.data()).containsEntry("policyDecision", "APPROVED");
        assertThat(executed.message()).contains("within the demo auto-approval limit");
    }

    @Test
    void retentionOfferExplainsDiscountCap() {
        RetentionStudioService.RetentionOfferResult executed = service.createOffer(
            new RetentionStudioService.RetentionOfferRequest("acct-1001", "user-2001", 50, true)
        );

        assertThat(executed.success()).isTrue();
        assertThat(executed.data()).containsEntry("requestedDiscountPercent", 50);
        assertThat(executed.data()).containsEntry("discountPercent", 40);
        assertThat(executed.data()).containsEntry("policyDecision", "APPROVED_WITH_CAP");
        assertThat(executed.message()).contains("capped");
    }

    @Test
    void releaseRegressionRoutesToEngineeringInsteadOfDiscount() {
        RetentionStudioService.RetentionReviewResult result = service.review(new RetentionStudioService.RetentionReviewRequest(
            "acct-1004",
            "user-1004",
            "pro",
            58,
            0,
            2,
            aiEvidence("ENGINEERING_ESCALATION", 0.86, "FRUSTRATED", "RAPIDLY_DECLINING",
                List.of("Escalate product regression", "Notify affected account team"))
        ));

        assertThat(result.riskCategory()).isEqualTo("HIGH");
        assertThat(result.actionFamily()).isEqualTo("ENGINEERING_ESCALATION");
        assertThat(result.recommendation()).contains("Escalate product regression");
        assertThat(result.policyExplanation()).contains("ENGINEERING_ESCALATION");
    }

    @Test
    void singleFailedPaymentIsMediumRiskUntilSignalsRepeat() {
        RetentionStudioService.RetentionReviewResult result = service.review(new RetentionStudioService.RetentionReviewRequest(
            "acct-1003",
            "user-1003",
            "starter",
            12,
            1,
            0,
            aiEvidence("ADOPTION_HELP", 0.48, "CONFUSED", "DECLINING",
                List.of("Schedule adoption review", "Share setup guidance"))
        ));

        assertThat(result.riskCategory()).isEqualTo("MEDIUM");
        assertThat(result.recommendation()).contains("adoption review");
    }

    @Test
    void reviewFailsWhenAiActionFamilyIsMissing() {
        assertThatThrownBy(() -> service.review(new RetentionStudioService.RetentionReviewRequest(
            "acct-1001",
            "user-2001",
            "pro",
            65,
            2,
            3,
            aiEvidence(null, 0.91, "CHURNING", "RAPIDLY_DECLINING", List.of("Assign CSM outreach"))
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("insights.action_family");
    }

    @Test
    void reviewFailsWhenAiActionFamilyIsUnsupported() {
        assertThatThrownBy(() -> service.review(new RetentionStudioService.RetentionReviewRequest(
            "acct-1001",
            "user-2001",
            "pro",
            65,
            2,
            3,
            aiEvidence("SEND_MAGIC_COUPON", 0.91, "CHURNING", "RAPIDLY_DECLINING", List.of("Assign CSM outreach"))
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unsupported action family");
    }

    private static RetentionStudioService.RetentionAiEvidence aiEvidence(String actionFamily,
                                                                         Double churnRisk,
                                                                         String sentiment,
                                                                         String trend,
                                                                         List<String> recommendations) {
        return new RetentionStudioService.RetentionAiEvidence(
            actionFamily,
            churnRisk,
            sentiment,
            trend,
            List.of("test-pattern"),
            recommendations,
            "Test churn reason",
            0.82,
            "test-model",
            churnRisk != null && churnRisk >= 0.8
        );
    }
}
