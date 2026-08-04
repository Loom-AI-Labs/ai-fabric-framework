package com.ai.fabric.realapps.agenticresolver.controller;

import ai.fabric.execution.action.ActionOutcomeView;
import ai.fabric.execution.review.ReviewTaskDetailView;
import ai.fabric.execution.review.ReviewTaskView;
import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.decision.ReviewDecisionResult;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import com.ai.fabric.realapps.agenticresolver.agentic.review.DemoReviewerSessionService;
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

/**
 * Public demo facade for durable human review. It issues opaque, short-lived
 * credentials and adds a browser-session boundary above framework review
 * authorization.
 */
@RestController
@Validated
@RequestMapping("/api/agentic-resolver/demo-reviews")
@ConditionalOnProperty(
    prefix = "ai.execution.reviews",
    name = "enabled",
    havingValue = "true"
)
public class DemoReviewController {

    private final DemoReviewerSessionService demoReviews;
    private final ObjectMapper objectMapper;

    public DemoReviewController(
        DemoReviewerSessionService demoReviews,
        ObjectMapper objectMapper
    ) {
        this.demoReviews = demoReviews;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/sessions")
    public DemoReviewerSessionService.IssuedReviewerSession issue(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
        String demoSessionId,
        @Valid @RequestBody IssueReviewerSessionRequest request
    ) {
        return demoReviews.issue(demoSessionId, request.role());
    }

    @GetMapping
    public List<ReviewTaskView> inbox(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
        String demoSessionId,
        @RequestHeader(DemoReviewerSessionService.REVIEW_SESSION_HEADER)
        String reviewerToken,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return demoReviews.inbox(demoSessionId, reviewerToken, limit);
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<DemoReviewTaskDetailResponse> detail(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
        String demoSessionId,
        @RequestHeader(DemoReviewerSessionService.REVIEW_SESSION_HEADER)
        String reviewerToken,
        @PathVariable String taskId
    ) {
        return demoReviews.detail(demoSessionId, reviewerToken, taskId)
            .map(this::toResponse)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{taskId}/decision")
    public ReviewDecisionResult decide(
        @RequestHeader(AgenticResolverController.SESSION_HEADER)
        String demoSessionId,
        @RequestHeader(DemoReviewerSessionService.REVIEW_SESSION_HEADER)
        String reviewerToken,
        @PathVariable String taskId,
        @Valid @RequestBody DecisionSubmission submission
    ) {
        return demoReviews.decide(
            demoSessionId,
            reviewerToken,
            new ReviewDecisionRequest(
                taskId,
                submission.decisionId(),
                submission.decision(),
                submission.expectedVersion(),
                toJson(submission.response())
            )
        );
    }

    private DemoReviewTaskDetailResponse toResponse(
        ReviewTaskDetailView detail
    ) {
        return new DemoReviewTaskDetailResponse(
            detail.task(),
            toMap(detail.requestedInformation()),
            toMap(detail.suppliedInformation()),
            detail.message(),
            detail.outcome(),
            detail.successorTaskId(),
            detail.failureReason()
        );
    }

    private JsonNode toJson(Map<String, Object> value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }

    private Map<String, Object> toMap(JsonNode value) {
        return value == null
            ? null
            : objectMapper.convertValue(
                value,
                new TypeReference<Map<String, Object>>() {}
            );
    }

    public record IssueReviewerSessionRequest(
        @NotNull DemoReviewerSessionService.ReviewerRole role
    ) {}

    public record DecisionSubmission(
        @NotBlank String decisionId,
        @NotNull ReviewDecisionType decision,
        long expectedVersion,
        Map<String, Object> response
    ) {}

    public record DemoReviewTaskDetailResponse(
        ReviewTaskView task,
        Map<String, Object> requestedInformation,
        Map<String, Object> suppliedInformation,
        String message,
        ActionOutcomeView outcome,
        String successorTaskId,
        String failureReason
    ) {}
}
