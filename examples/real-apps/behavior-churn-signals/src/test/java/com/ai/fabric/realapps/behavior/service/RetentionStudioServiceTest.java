package com.ai.fabric.realapps.behavior.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
            3
        ));

        assertThat(result.riskCategory()).isEqualTo("HIGH");
        assertThat(result.actionFamily()).isEqualTo("RETENTION_OFFER");
        assertThat(result.evidenceIds()).contains("insight-acct-1001-user-2001", "plan-pro");
        assertThat(result.recommendation()).contains("Offer retention credit");
        assertThat(result.policyExplanation()).contains("confirmation-gated retention offer");
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
            2
        ));

        assertThat(result.riskCategory()).isEqualTo("HIGH");
        assertThat(result.actionFamily()).isEqualTo("ENGINEERING_ESCALATION");
        assertThat(result.recommendation()).contains("engineering");
        assertThat(result.policyExplanation()).contains("safer than discounting");
    }

    @Test
    void singleFailedPaymentIsMediumRiskUntilSignalsRepeat() {
        RetentionStudioService.RetentionReviewResult result = service.review(new RetentionStudioService.RetentionReviewRequest(
            "acct-1003",
            "user-1003",
            "starter",
            12,
            1,
            0
        ));

        assertThat(result.riskCategory()).isEqualTo("MEDIUM");
        assertThat(result.recommendation()).contains("adoption review");
    }
}
