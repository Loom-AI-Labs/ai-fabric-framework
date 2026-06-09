package com.ai.fabric.realapps.chat.reviews.action;

import com.ai.fabric.realapps.chat.reviews.domain.Review;
import com.ai.fabric.realapps.chat.reviews.service.ReviewService;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.ActionTargetRef;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionConfirmation;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "add_review",
    description = "Add a review for a product (this is searchable)",
    category = "commerce",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class AddReviewActionHandler {

    private final ReviewService reviewService;

    @ActionConfirmation
    public String confirm() {
        return "Submit this review?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "productId", description = "Product id") Long productId,
        @Param(value = "sku", description = "Product sku") String sku,
        @Param(value = "rating", description = "Rating 1-5", required = true, min = 1, max = 5) Integer rating,
        @Param(value = "text", description = "Review text", required = true) String text,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            Review created = reviewService.create(userId, productId, sku, rating, text);
            String reviewId = created != null && created.getId() != null ? String.valueOf(created.getId()) : null;
            ActionTargetRef reviewTarget = reviewId != null
                ? new ActionTargetRef(reviewId, "review", "review", Map.of("reviewId", reviewId))
                : null;
            return ActionResult.builder()
                .success(true)
                .message("Review submitted")
                .data(ActionResultContracts.object(Map.of(
                    "reviewId", created.getId(),
                    "rating", created.getRating(),
                    "productId", created.getProductId(),
                    "sku", created.getSku()
                )))
                .pinnedTargets(reviewTarget != null ? List.of(reviewTarget) : null)
                .build();
        } catch (Exception e) {
            log.error("Add review failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to add review: " + e.getMessage())
                .errorCode("ADD_REVIEW_FAILED")
                .build();
        }
    }
}
