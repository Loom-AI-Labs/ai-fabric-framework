package com.ai.fabric.realapps.behavior.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class RetentionStudioService {

    public RetentionReviewResult review(RetentionReviewRequest request) {
        RetentionReviewRequest effective = requireRequest(request);
        String risk = riskCategory(effective);
        String insightId = "insight-" + effective.accountId() + "-" + effective.userId();
        String planEvidenceId = "plan-" + effective.planId();
        return new RetentionReviewResult(
            effective.accountId(),
            effective.userId(),
            risk,
            List.of(insightId, planEvidenceId),
            recommendation(risk)
        );
    }

    public RetentionOfferResult createOffer(RetentionOfferRequest request) {
        if (request == null || !StringUtils.hasText(request.accountId()) || !StringUtils.hasText(request.userId())) {
            throw new IllegalArgumentException("accountId and userId are required");
        }
        if (!request.confirmed()) {
            return new RetentionOfferResult(false, true, "Confirm retention offer", null, Map.of(
                "accountId", request.accountId(),
                "userId", request.userId()
            ));
        }
        return new RetentionOfferResult(true, false, "Retention offer created", null, Map.of(
            "offerId", "ret-" + request.accountId() + "-" + request.userId(),
            "accountId", request.accountId(),
            "userId", request.userId(),
            "discountPercent", Math.min(Math.max(request.discountPercent(), 0), 40)
        ));
    }

    private RetentionReviewRequest requireRequest(RetentionReviewRequest request) {
        if (request == null
            || !StringUtils.hasText(request.accountId())
            || !StringUtils.hasText(request.userId())
            || !StringUtils.hasText(request.planId())) {
            throw new IllegalArgumentException("accountId, userId, and planId are required");
        }
        return request;
    }

    private String riskCategory(RetentionReviewRequest request) {
        if (request.failedPayments() > 0 || request.usageDropPercent() >= 50 || request.supportTickets() >= 3) {
            return "HIGH";
        }
        if (request.usageDropPercent() >= 25 || request.supportTickets() > 0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String recommendation(String risk) {
        return switch (risk) {
            case "HIGH" -> "Offer retention credit and assign CSM outreach.";
            case "MEDIUM" -> "Schedule adoption review and share plan guidance.";
            default -> "Continue monitoring behavior trend.";
        };
    }

    public record RetentionReviewRequest(
        String accountId,
        String userId,
        String planId,
        int usageDropPercent,
        int failedPayments,
        int supportTickets
    ) {}

    public record RetentionReviewResult(
        String accountId,
        String userId,
        String riskCategory,
        List<String> evidenceIds,
        String recommendation
    ) {}

    public record RetentionOfferRequest(
        String accountId,
        String userId,
        int discountPercent,
        boolean confirmed
    ) {}

    public record RetentionOfferResult(
        boolean success,
        boolean confirmationRequired,
        String message,
        String errorCode,
        Map<String, Object> data
    ) {}
}
