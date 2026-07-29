package com.ai.fabric.realapps.agenticresolver.agentic.review;

import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;

public final class SupportCreditReviewPolicies {

    public static final ReviewPolicyId STANDARD =
        ReviewPolicyId.of("support-credit-review", "1");
    public static final ReviewPolicyId SENIOR =
        ReviewPolicyId.of("support-credit-senior-review", "1");
    public static final SpecialistSchemaId CORRECTION_SCHEMA =
        new SpecialistSchemaId("support-credit-correction", "1");
    public static final SpecialistSchemaId INFORMATION_REQUEST_SCHEMA =
        new SpecialistSchemaId(
            "support-credit-information-request",
            "1"
        );
    public static final SpecialistSchemaId INFORMATION_RESPONSE_SCHEMA =
        new SpecialistSchemaId(
            "support-credit-information-response",
            "1"
        );
    public static final String REVIEWER_AUTHORIZER =
        "support-credit-reviewer-authorizer@1";
    public static final String DISPATCHER = "local-review-inbox@1";
    public static final String CORRECTION_HANDLER =
        "support-credit-correction@1";
    public static final String INFORMATION_HANDLER =
        "support-credit-information@1";
    public static final String REVIEW_SCOPE = "review:support-credit";
    public static final String SENIOR_REVIEW_SCOPE =
        "review:support-credit:senior";

    private SupportCreditReviewPolicies() {}
}
