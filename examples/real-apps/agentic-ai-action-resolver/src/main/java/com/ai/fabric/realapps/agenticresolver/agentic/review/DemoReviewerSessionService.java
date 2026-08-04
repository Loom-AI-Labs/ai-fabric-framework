package com.ai.fabric.realapps.agenticresolver.agentic.review;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.review.ReviewTaskDetailView;
import ai.fabric.execution.review.ReviewTaskView;
import ai.fabric.execution.review.TrustedReviewerContext;
import ai.fabric.execution.review.decision.ReviewDecisionRequest;
import ai.fabric.execution.review.decision.ReviewDecisionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverSessionService;
import com.ai.fabric.realapps.agenticresolver.entity.DemoReviewTaskBinding;
import com.ai.fabric.realapps.agenticresolver.entity.DemoReviewerSession;
import com.ai.fabric.realapps.agenticresolver.repository.DemoReviewTaskBindingRepository;
import com.ai.fabric.realapps.agenticresolver.repository.DemoReviewerSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(
    prefix = "ai.execution.reviews",
    name = "enabled",
    havingValue = "true"
)
public class DemoReviewerSessionService {

    public static final String REVIEW_SESSION_HEADER =
        "X-AI-Fabric-Demo-Reviewer";

    private final AgenticResolverSessionService demoSessions;
    private final DemoReviewerSessionRepository reviewerSessions;
    private final DemoReviewTaskBindingRepository taskBindings;
    private final SupportCreditReviewService reviews;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public DemoReviewerSessionService(
        AgenticResolverSessionService demoSessions,
        DemoReviewerSessionRepository reviewerSessions,
        DemoReviewTaskBindingRepository taskBindings,
        SupportCreditReviewService reviews,
        Clock clock,
        @Value("${app.agentic-resolver.reviews.demo-session-ttl:PT30M}")
        Duration ttl
    ) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                "Demo reviewer session TTL must be positive"
            );
        }
        this.demoSessions = demoSessions;
        this.reviewerSessions = reviewerSessions;
        this.taskBindings = taskBindings;
        this.reviews = reviews;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional
    public IssuedReviewerSession issue(
        String demoSessionId,
        ReviewerRole role
    ) {
        demoSessions.active(demoSessionId);
        Instant now = clock.instant();
        String token = token();
        reviewerSessions.save(new DemoReviewerSession(
            hash(token),
            demoSessionId,
            role.name(),
            now,
            now.plus(ttl)
        ));
        return new IssuedReviewerSession(token, role, now.plus(ttl));
    }

    @Transactional(readOnly = true)
    public List<ReviewTaskView> inbox(
        String demoSessionId,
        String token,
        int limit
    ) {
        AuthorizedReviewer reviewer = authorize(demoSessionId, token);
        Set<String> allowedTaskIds = taskBindings
            .findByDemoSessionId(demoSessionId)
            .stream()
            .map(DemoReviewTaskBinding::taskId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return reviews.inbox(reviewer.context(), limit).stream()
            .filter(task -> allowedTaskIds.contains(task.taskId()))
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ReviewTaskDetailView> detail(
        String demoSessionId,
        String token,
        String taskId
    ) {
        AuthorizedReviewer reviewer = authorize(demoSessionId, token);
        requireTaskBinding(demoSessionId, taskId);
        return reviews.detail(taskId, reviewer.context());
    }

    @Transactional
    public ReviewDecisionResult decide(
        String demoSessionId,
        String token,
        ReviewDecisionRequest request
    ) {
        AuthorizedReviewer reviewer = authorize(demoSessionId, token);
        requireTaskBinding(demoSessionId, request.taskId());
        ReviewDecisionResult result = reviews.decide(
            request,
            reviewer.context()
        );
        if (result.successorTaskId() != null) {
            bindTask(demoSessionId, result.successorTaskId());
        }
        return result;
    }

    @Transactional
    public void bindTask(String demoSessionId, String taskId) {
        DemoReviewTaskBinding existing = taskBindings.findById(taskId)
            .orElse(null);
        if (existing != null) {
            if (!MessageDigest.isEqual(
                    existing.demoSessionId().getBytes(StandardCharsets.UTF_8),
                    demoSessionId.getBytes(StandardCharsets.UTF_8)
                )) {
                throw new IllegalStateException(
                    "Review task is already bound to another demo session"
                );
            }
            return;
        }
        taskBindings.save(new DemoReviewTaskBinding(
            taskId,
            demoSessionId,
            clock.instant()
        ));
    }

    @Transactional
    public long revoke(String demoSessionId) {
        return reviewerSessions.deleteByDemoSessionId(demoSessionId);
    }

    @Scheduled(
        cron = "${app.agentic-resolver.reviews.demo-cleanup-cron:0 */15 * * * *}"
    )
    @Transactional
    public long cleanupExpired() {
        return reviewerSessions.deleteByExpiresAtBefore(clock.instant());
    }

    private AuthorizedReviewer authorize(
        String demoSessionId,
        String token
    ) {
        demoSessions.active(demoSessionId);
        DemoReviewerSession record = reviewerSessions.findById(hash(token))
            .orElseThrow(() -> unauthorized(
                "A valid demo reviewer session is required."
            ));
        if (!MessageDigest.isEqual(
                record.demoSessionId().getBytes(StandardCharsets.UTF_8),
                demoSessionId.getBytes(StandardCharsets.UTF_8)
            ) || !record.expiresAt().isAfter(clock.instant())) {
            throw unauthorized("The demo reviewer session expired or is invalid.");
        }
        ReviewerRole role = ReviewerRole.valueOf(record.reviewerRole());
        Set<String> scopes = role == ReviewerRole.SENIOR
            ? Set.of(
                SupportCreditReviewPolicies.REVIEW_SCOPE,
                SupportCreditReviewPolicies.SENIOR_REVIEW_SCOPE
            )
            : Set.of(SupportCreditReviewPolicies.REVIEW_SCOPE);
        TrustedReviewerContext context = new TrustedReviewerContext(
            new ExecutionPrincipal(
                "demo-reviewer:"
                    + hash(demoSessionId).substring(0, 16)
                    + ":"
                    + role.name().toLowerCase(Locale.ROOT),
                ExecutionPrincipalType.END_USER
            ),
            "public-demo",
            scopes,
            "demo-review-" + UUID.randomUUID(),
            clock.instant()
        );
        return new AuthorizedReviewer(role, context);
    }

    private void requireTaskBinding(String demoSessionId, String taskId) {
        DemoReviewTaskBinding binding = taskBindings.findById(taskId)
            .orElseThrow(() -> new NoSuchElementException(
                "Review task was not found for this demo session"
            ));
        if (!MessageDigest.isEqual(
                binding.demoSessionId().getBytes(StandardCharsets.UTF_8),
                demoSessionId.getBytes(StandardCharsets.UTF_8)
            )) {
            throw new NoSuchElementException(
                "Review task was not found for this demo session"
            );
        }
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw unauthorized("A valid demo reviewer session is required.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private ResponseStatusException unauthorized(String message) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }

    public enum ReviewerRole {
        REGULAR,
        SENIOR
    }

    public record IssuedReviewerSession(
        String token,
        ReviewerRole role,
        Instant expiresAt
    ) {}

    private record AuthorizedReviewer(
        ReviewerRole role,
        TrustedReviewerContext context
    ) {}
}
