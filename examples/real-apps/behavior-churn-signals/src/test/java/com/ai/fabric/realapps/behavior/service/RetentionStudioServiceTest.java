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
            0,
            1
        ));

        assertThat(result.riskCategory()).isEqualTo("HIGH");
        assertThat(result.evidenceIds()).contains("insight-acct-1001-user-2001", "plan-pro");
        assertThat(result.recommendation()).contains("Offer retention credit");
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
