package com.ai.fabric.realapps.behavior.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class RetentionStudioService {

    private static final int MAX_AUTO_DISCOUNT_PERCENT = 40;

    public RetentionReviewResult review(RetentionReviewRequest request) {
        RetentionReviewRequest effective = requireRequest(request);
        String risk = riskCategory(effective);
        String actionFamily = actionFamily(effective, risk);
        String insightId = "insight-" + effective.accountId() + "-" + effective.userId();
        String planEvidenceId = "plan-" + effective.planId();
        return new RetentionReviewResult(
            effective.accountId(),
            effective.userId(),
            risk,
            actionFamily,
            List.of(insightId, planEvidenceId),
            recommendation(actionFamily, risk),
            policyExplanation(actionFamily, risk)
        );
    }

    public RetentionOfferResult createOffer(RetentionOfferRequest request) {
        if (request == null || !StringUtils.hasText(request.accountId()) || !StringUtils.hasText(request.userId())) {
            throw new IllegalArgumentException("accountId and userId are required");
        }
        int requestedDiscount = Math.max(request.discountPercent(), 0);
        int approvedDiscount = Math.min(requestedDiscount, MAX_AUTO_DISCOUNT_PERCENT);
        String policyDecision = requestedDiscount > MAX_AUTO_DISCOUNT_PERCENT
            ? "APPROVED_WITH_CAP"
            : "APPROVED";
        String policyExplanation = requestedDiscount > MAX_AUTO_DISCOUNT_PERCENT
            ? "Requested discount exceeded the " + MAX_AUTO_DISCOUNT_PERCENT + "% demo maximum, so the offer was capped."
            : "Requested discount is within the demo auto-approval limit.";
        if (!request.confirmed()) {
            return new RetentionOfferResult(false, true, "Confirm retention offer. " + policyExplanation, null, Map.of(
                "accountId", request.accountId(),
                "userId", request.userId(),
                "requestedDiscountPercent", requestedDiscount,
                "discountPercent", approvedDiscount,
                "maxDiscountPercent", MAX_AUTO_DISCOUNT_PERCENT,
                "policyDecision", "CONFIRMATION_REQUIRED",
                "policyExplanation", policyExplanation
            ));
        }
        return new RetentionOfferResult(true, false, "Retention offer created. " + policyExplanation, null, Map.of(
            "offerId", "ret-" + request.accountId() + "-" + request.userId(),
            "accountId", request.accountId(),
            "userId", request.userId(),
            "requestedDiscountPercent", requestedDiscount,
            "discountPercent", approvedDiscount,
            "maxDiscountPercent", MAX_AUTO_DISCOUNT_PERCENT,
            "policyDecision", policyDecision,
            "policyExplanation", policyExplanation
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
        if (request.failedPayments() >= 2 || request.usageDropPercent() >= 50 || request.supportTickets() >= 3) {
            return "HIGH";
        }
        if (request.failedPayments() > 0 || request.usageDropPercent() >= 25 || request.supportTickets() > 0) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String actionFamily(RetentionReviewRequest request, String risk) {
        if (request.failedPayments() == 0 && request.supportTickets() >= 2 && request.usageDropPercent() >= 50) {
            return "ENGINEERING_ESCALATION";
        }
        if (request.failedPayments() == 0 && request.supportTickets() == 0 && request.usageDropPercent() >= 25) {
            return "PROACTIVE_CHECK_IN";
        }
        if (request.failedPayments() == 0 && request.supportTickets() > 0) {
            return "ADOPTION_HELP";
        }
        if ("LOW".equals(risk) && "enterprise".equalsIgnoreCase(request.planId())) {
            return "EXPANSION_FOLLOW_UP";
        }
        if ("HIGH".equals(risk)) {
            return "RETENTION_OFFER";
        }
        if ("MEDIUM".equals(risk)) {
            return "ADOPTION_HELP";
        }
        return "MONITOR_ONLY";
    }

    private String recommendation(String actionFamily, String risk) {
        return switch (actionFamily) {
            case "RETENTION_OFFER" -> "Offer retention credit and assign CSM outreach.";
            case "ENGINEERING_ESCALATION" -> "Escalate product regression evidence to engineering and notify the account team.";
            case "ADOPTION_HELP" -> "Schedule adoption review and share setup guidance.";
            case "EXPANSION_FOLLOW_UP" -> "Route to expansion follow-up and avoid unnecessary retention discount.";
            case "PROACTIVE_CHECK_IN" -> "Send proactive check-in before quiet disengagement becomes cancellation risk.";
            default -> "Continue monitoring behavior trend.";
        };
    }

    private String policyExplanation(String actionFamily, String risk) {
        return switch (actionFamily) {
            case "RETENTION_OFFER" -> "High risk with commercial friction qualifies for a confirmation-gated retention offer.";
            case "ENGINEERING_ESCALATION" -> "Usage dropped with repeated support/product-error signals, so escalation is safer than discounting.";
            case "ADOPTION_HELP" -> "The account shows support or setup friction without enough commercial distress for a retention discount.";
            case "EXPANSION_FOLLOW_UP" -> "Healthy enterprise behavior should be routed to expansion, not retention rescue.";
            case "PROACTIVE_CHECK_IN" -> "Usage is declining without explicit complaints, so the safe next step is proactive outreach.";
            default -> "Risk is " + risk + ", so monitoring is sufficient for now.";
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
        String actionFamily,
        List<String> evidenceIds,
        String recommendation,
        String policyExplanation
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
