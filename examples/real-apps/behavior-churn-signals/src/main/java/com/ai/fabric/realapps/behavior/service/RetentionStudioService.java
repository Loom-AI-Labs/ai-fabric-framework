package com.ai.fabric.realapps.behavior.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RetentionStudioService {

    private static final int MAX_AUTO_DISCOUNT_PERCENT = 40;
    private static final Set<String> ALLOWED_ACTION_FAMILIES = Set.of(
        "RETENTION_OFFER",
        "EXPANSION_FOLLOW_UP",
        "ADOPTION_HELP",
        "ENGINEERING_ESCALATION",
        "PROACTIVE_CHECK_IN",
        "MONITOR_ONLY"
    );

    public RetentionReviewResult review(RetentionReviewRequest request) {
        RetentionReviewRequest effective = requireRequest(request);
        RetentionAiEvidence evidence = requireAiEvidence(effective.aiEvidence(), effective.userId());
        String actionFamily = normalizeActionFamily(evidence.actionFamily(), effective.userId());
        String risk = riskCategory(evidence);
        String insightId = "insight-" + effective.accountId() + "-" + effective.userId();
        String planEvidenceId = "plan-" + effective.planId();
        return new RetentionReviewResult(
            effective.accountId(),
            effective.userId(),
            risk,
            actionFamily,
            List.of(insightId, planEvidenceId, "ai-action-" + actionFamily.toLowerCase(Locale.ROOT)),
            recommendation(evidence),
            policyExplanation(actionFamily, risk, evidence)
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

    private RetentionAiEvidence requireAiEvidence(RetentionAiEvidence evidence, String userId) {
        if (evidence == null) {
            throw new IllegalStateException("AI behavior analysis is required before retention review for user " + userId);
        }
        if (evidence.recommendations() == null || evidence.recommendations().stream().noneMatch(StringUtils::hasText)) {
            throw new IllegalStateException("AI behavior analysis did not provide recommendations for user " + userId);
        }
        return evidence;
    }

    private String normalizeActionFamily(String rawActionFamily, String userId) {
        if (!StringUtils.hasText(rawActionFamily)) {
            throw new IllegalStateException("AI behavior analysis did not provide insights.action_family for user " + userId);
        }
        String normalized = rawActionFamily.trim()
            .replace('-', '_')
            .replace(' ', '_')
            .toUpperCase(Locale.ROOT);
        if (!ALLOWED_ACTION_FAMILIES.contains(normalized)) {
            throw new IllegalStateException("AI behavior analysis returned unsupported action family '" + rawActionFamily + "' for user " + userId);
        }
        return normalized;
    }

    private String riskCategory(RetentionAiEvidence evidence) {
        if (Boolean.TRUE.equals(evidence.requiresImmediateAction()) || value(evidence.churnRisk()) >= 0.75) {
            return "HIGH";
        }
        String trend = evidence.trend() != null ? evidence.trend().toUpperCase(Locale.ROOT) : "";
        String sentiment = evidence.sentimentLabel() != null ? evidence.sentimentLabel().toUpperCase(Locale.ROOT) : "";
        if (value(evidence.churnRisk()) >= 0.35
            || trend.contains("DECLINING")
            || "CONFUSED".equals(sentiment)
            || "FRUSTRATED".equals(sentiment)
            || "CHURNING".equals(sentiment)) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private double value(Double value) {
        return value != null ? value : 0.0;
    }

    private String recommendation(RetentionAiEvidence evidence) {
        return evidence.recommendations().stream()
            .filter(StringUtils::hasText)
            .limit(3)
            .reduce((left, right) -> left + "; " + right)
            .orElseThrow();
    }

    private String policyExplanation(String actionFamily, String risk, RetentionAiEvidence evidence) {
        return "AI Fabric accepted the LLM-selected action family " + actionFamily
            + " for analytics review after validating it against the allowed demo policy categories. "
            + "Risk is " + risk
            + " from churnRisk=" + String.format(Locale.ROOT, "%.2f", value(evidence.churnRisk()))
            + ", trend=" + safe(evidence.trend())
            + ", sentiment=" + safe(evidence.sentimentLabel())
            + ". This page explains operator insight only; it does not execute a customer-facing offer.";
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "unknown";
    }

    public record RetentionReviewRequest(
        String accountId,
        String userId,
        String planId,
        int usageDropPercent,
        int failedPayments,
        int supportTickets,
        RetentionAiEvidence aiEvidence
    ) {}

    public record RetentionAiEvidence(
        String actionFamily,
        Double churnRisk,
        String sentimentLabel,
        String trend,
        List<String> patterns,
        List<String> recommendations,
        String churnReason,
        Double confidence,
        String model,
        Boolean requiresImmediateAction
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
