package ai.fabric.execution.review.auth;

import ai.fabric.execution.review.TrustedReviewerContext;

public interface ReviewerAuthorizer {

    String id();

    ReviewerAuthorization authorize(
        ReviewAuthorizationRequest request,
        TrustedReviewerContext reviewer
    );
}
