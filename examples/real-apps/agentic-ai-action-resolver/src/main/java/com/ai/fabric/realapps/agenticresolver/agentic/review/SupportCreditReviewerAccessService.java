package com.ai.fabric.realapps.agenticresolver.agentic.review;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.review.TrustedReviewerContext;
import com.ai.fabric.realapps.agenticresolver.config.SupportCreditReviewAccessProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(
    prefix = "ai.execution.reviews",
    name = "enabled",
    havingValue = "true"
)
public class SupportCreditReviewerAccessService {

    private final byte[] reviewerKey;
    private final byte[] seniorReviewerKey;
    private final Clock clock;

    public SupportCreditReviewerAccessService(
        SupportCreditReviewAccessProperties properties,
        Clock clock
    ) {
        this.reviewerKey = key(
            properties.reviewerApiKey(),
            "APP_REVIEWER_API_KEY"
        );
        this.seniorReviewerKey = key(
            properties.seniorReviewerApiKey(),
            "APP_SENIOR_REVIEWER_API_KEY"
        );
        if (MessageDigest.isEqual(reviewerKey, seniorReviewerKey)) {
            throw new IllegalStateException(
                "Regular and senior reviewer API keys must be distinct"
            );
        }
        this.clock = clock;
    }

    public TrustedReviewerContext authenticate(String suppliedKey) {
        byte[] candidate = suppliedKey == null
            ? new byte[0]
            : suppliedKey.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(candidate, seniorReviewerKey)) {
            return context(
                "senior-account-operations-reviewer",
                Set.of(
                    SupportCreditReviewPolicies.REVIEW_SCOPE,
                    SupportCreditReviewPolicies.SENIOR_REVIEW_SCOPE
                )
            );
        }
        if (MessageDigest.isEqual(candidate, reviewerKey)) {
            return context(
                "account-operations-reviewer",
                Set.of(SupportCreditReviewPolicies.REVIEW_SCOPE)
            );
        }
        throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "A valid review API key is required."
        );
    }

    private TrustedReviewerContext context(
        String principalId,
        Set<String> scopes
    ) {
        return new TrustedReviewerContext(
            new ExecutionPrincipal(
                principalId,
                ExecutionPrincipalType.SERVICE
            ),
            "public-demo",
            scopes,
            "review-" + UUID.randomUUID(),
            clock.instant()
        );
    }

    private byte[] key(String value, String environmentName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 16) {
            throw new IllegalStateException(
                environmentName
                    + " must contain at least 16 characters when reviews are enabled"
            );
        }
        return normalized.getBytes(StandardCharsets.UTF_8);
    }
}
