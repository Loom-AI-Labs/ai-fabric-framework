package com.ai.fabric.realapps.agenticresolver.controller;

import ai.fabric.execution.action.ActionOutcomeView;
import ai.fabric.execution.review.ReviewTaskDetailView;
import ai.fabric.execution.review.ReviewTaskView;
import ai.fabric.execution.review.TrustedReviewerContext;
import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.decision.ReviewDecisionResult;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.input.ReviewInformationResult;
import ai.fabric.execution.review.input.ReviewInformationSubmission;
import com.ai.fabric.realapps.agenticresolver.agentic.SupportCreditReviewRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.SupportCreditReviewSubmissionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.review.SupportCreditReviewService;
import com.ai.fabric.realapps.agenticresolver.agentic.review.SupportCreditReviewerAccessService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/agentic-resolver/reviews")
@ConditionalOnProperty(
    prefix = "ai.execution.reviews",
    name = "enabled",
    havingValue = "true"
)
public class SupportCreditReviewController {

    public static final String REVIEW_KEY_HEADER =
        "X-AI-Fabric-Review-Key";

    private final SupportCreditReviewService reviews;
    private final SupportCreditReviewerAccessService reviewerAccess;
    private final ObjectMapper objectMapper;

    public SupportCreditReviewController(
        SupportCreditReviewService reviews,
        SupportCreditReviewerAccessService reviewerAccess,
        ObjectMapper objectMapper
    ) {
        this.reviews = reviews;
        this.reviewerAccess = reviewerAccess;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/support-credit")
    public SupportCreditReviewSubmissionResult propose(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
        String sessionId,
        @RequestHeader(AgenticResolverController.IDEMPOTENCY_HEADER)
        String idempotencyKey,
        @Valid @RequestBody SupportCreditReviewRequest request
    ) {
        return reviews.propose(sessionId, request, idempotencyKey);
    }

    @GetMapping
    public List<ReviewTaskView> inbox(
        @RequestHeader(REVIEW_KEY_HEADER) String reviewKey,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return reviews.inbox(
            reviewerAccess.authenticate(reviewKey),
            limit
        );
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ReviewTaskDetailResponse> detail(
        @RequestHeader(REVIEW_KEY_HEADER) String reviewKey,
        @PathVariable String taskId
    ) {
        return reviews.detail(
                taskId,
                reviewerAccess.authenticate(reviewKey)
            )
            .map(this::toResponse)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{taskId}/decision")
    public ReviewDecisionResult decide(
        @RequestHeader(REVIEW_KEY_HEADER) String reviewKey,
        @PathVariable String taskId,
        @Valid @RequestBody DecisionSubmission submission
    ) {
        TrustedReviewerContext reviewer =
            reviewerAccess.authenticate(reviewKey);
        return reviews.decide(
            new ReviewDecisionRequest(
                taskId,
                submission.decisionId(),
                submission.decision(),
                submission.expectedVersion(),
                toJson(submission.response())
            ),
            reviewer
        );
    }

    @PostMapping("/{taskId}/information")
    public ReviewInformationResult provideInformation(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
        String sessionId,
        @PathVariable String taskId,
        @Valid @RequestBody InformationSubmission submission
    ) {
        return reviews.provideInformation(
            sessionId,
            new ReviewInformationSubmission(
                taskId,
                submission.submissionId(),
                submission.expectedVersion(),
                toJson(submission.response())
            )
        );
    }

    private JsonNode toJson(Map<String, Object> response) {
        return response == null ? null : objectMapper.valueToTree(response);
    }

    private ReviewTaskDetailResponse toResponse(ReviewTaskDetailView detail) {
        return new ReviewTaskDetailResponse(
            detail.task(),
            toMap(detail.requestedInformation()),
            toMap(detail.suppliedInformation()),
            detail.message(),
            detail.outcome(),
            detail.successorTaskId(),
            detail.failureReason()
        );
    }

    private Map<String, Object> toMap(JsonNode value) {
        return value == null
            ? null
            : objectMapper.convertValue(
                value,
                new TypeReference<Map<String, Object>>() {}
            );
    }

    public record ReviewTaskDetailResponse(
        ReviewTaskView task,
        Map<String, Object> requestedInformation,
        Map<String, Object> suppliedInformation,
        String message,
        ActionOutcomeView outcome,
        String successorTaskId,
        String failureReason
    ) {}

    public record DecisionSubmission(
        @NotBlank String decisionId,
        @NotNull ReviewDecisionType decision,
        long expectedVersion,
        Map<String, Object> response
    ) {}

    public record InformationSubmission(
        @NotBlank String submissionId,
        long expectedVersion,
        @NotNull Map<String, Object> response
    ) {}
}
