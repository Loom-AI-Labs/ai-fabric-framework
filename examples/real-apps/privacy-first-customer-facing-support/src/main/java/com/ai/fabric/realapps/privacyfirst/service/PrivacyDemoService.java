package com.ai.fabric.realapps.privacyfirst.service;

import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PrivacyDemoService {

    private static final Pattern SAFE_SESSION_ID = Pattern.compile("[^A-Za-z0-9_.:-]");
    private static final int MAX_LIMIT = 10;

    private final SupportMessageService supportMessageService;

    @Transactional
    public DemoDashboard createSession(String requestedSessionId) {
        String sessionId = normalizeSessionId(requestedSessionId);
        supportMessageService.deleteByCustomerIdPrefix(prefix(sessionId));
        for (DemoSample sample : samples()) {
            supportMessageService.create(
                customerId(sessionId, sample.personaId()),
                sample.channel(),
                sample.subject(),
                sample.message()
            );
        }
        return dashboard(sessionId);
    }

    @Transactional(readOnly = true)
    public DemoDashboard dashboard(String sessionId) {
        String normalizedSessionId = normalizeExistingSessionId(sessionId);
        List<PrivacyMessageCard> messages = supportMessageService.findByCustomerIdPrefix(prefix(normalizedSessionId)).stream()
            .map(message -> toCard(normalizedSessionId, message))
            .toList();
        return new DemoDashboard(
            normalizedSessionId,
            samples(),
            messages,
            metrics(messages),
            List.of(
                new PipelineStage("Capture", "Raw user message enters the support intake API.", "app"),
                new PipelineStage("PII Detection", "AI Fabric PIIDetectionService detects and redacts sensitive fields.", "ai-fabric"),
                new PipelineStage("Safe Persistence", "Only processed subject/message fields are exposed by the demo API.", "app"),
                new PipelineStage("Safe Indexing", "AI Fabric indexes the redacted support-message entity for search.", "ai-fabric"),
                new PipelineStage("Governance", "Customer inventory/deletion can operate on privacy-safe records.", "ai-fabric")
            )
        );
    }

    @Transactional
    public MessageResult submitMessage(SubmitMessageRequest request) {
        String sessionId = normalizeExistingSessionId(request.sessionId());
        String personaId = normalizePersonaId(request.personaId());
        SupportMessage saved = supportMessageService.create(
            customerId(sessionId, personaId),
            valueOrDefault(request.channel(), "webchat"),
            valueOrDefault(request.subject(), "Customer support request"),
            requiredText(request.message(), "message")
        );
        return new MessageResult(toCard(sessionId, saved), dashboard(sessionId));
    }

    @Transactional(readOnly = true)
    public SearchResult search(String sessionId, String query, int requestedLimit) {
        String normalizedSessionId = normalizeExistingSessionId(sessionId);
        String rawQuery = requiredText(query, "query");
        SupportMessageService.PrivacyProcessingEvidence queryEvidence = supportMessageService.processForPrivacy(rawQuery);
        List<PrivacyMessageCard> results = supportMessageService.semanticSearch(rawQuery, clampLimit(requestedLimit)).stream()
            .filter(message -> message.getCustomerId() != null && message.getCustomerId().startsWith(prefix(normalizedSessionId)))
            .map(message -> toCard(normalizedSessionId, message))
            .toList();
        return new SearchResult(
            normalizedSessionId,
            queryEvidence.processedText(),
            queryEvidence.piiDetected(),
            queryEvidence.detectionsSummary(),
            results.size(),
            results
        );
    }

    public List<DemoSample> samples() {
        return List.of(
            new DemoSample(
                "billing",
                "Billing Support",
                "webchat",
                "Billing update request",
                "Hi, my email is sara.ahmed@example.com and my phone is +1 (555) 123-4567. Please update my subscription.",
                List.of("EMAIL", "PHONE")
            ),
            new DemoSample(
                "refund",
                "Refund Review",
                "email",
                "Refund request",
                "Order 81273 - my SSN is 123-45-6789 for verification and my phone is 555-222-1111.",
                List.of("SSN", "PHONE")
            ),
            new DemoSample(
                "clean",
                "Clean Login Help",
                "mobile",
                "Login issue",
                "I cannot login after password reset. No PII here, just help me regain access.",
                List.of()
            )
        );
    }

    private PrivacyMessageCard toCard(String sessionId, SupportMessage message) {
        String personaId = personaId(sessionId, message.getCustomerId());
        return new PrivacyMessageCard(
            message.getId(),
            personaId,
            displayName(personaId),
            message.getChannel(),
            message.isPiiDetected(),
            message.getModeApplied(),
            message.getDetectionsCount(),
            message.getDetectionsSummary(),
            message.getProcessedSubject(),
            message.getProcessedMessage(),
            originalEvidencePolicy(message),
            true,
            message.getCreatedAt()
        );
    }

    private PrivacyMetrics metrics(List<PrivacyMessageCard> messages) {
        long piiMessages = messages.stream().filter(PrivacyMessageCard::piiDetected).count();
        return new PrivacyMetrics(
            messages.size(),
            (int) piiMessages,
            messages.size() - (int) piiMessages,
            messages.stream().mapToInt(PrivacyMessageCard::detectionsCount).sum(),
            messages.stream().allMatch(PrivacyMessageCard::rawInputWithheld)
        );
    }

    private String normalizeSessionId(String requestedSessionId) {
        String candidate = StringUtils.hasText(requestedSessionId) ? requestedSessionId.trim() : "privacy-" + UUID.randomUUID();
        String sanitized = SAFE_SESSION_ID.matcher(candidate).replaceAll("-");
        if (!StringUtils.hasText(sanitized)) {
            return "privacy-" + UUID.randomUUID();
        }
        return sanitized.length() > 80 ? sanitized.substring(0, 80) : sanitized;
    }

    private String normalizeExistingSessionId(String sessionId) {
        return normalizeSessionId(requiredText(sessionId, "sessionId"));
    }

    private String normalizePersonaId(String personaId) {
        String sanitized = SAFE_SESSION_ID.matcher(valueOrDefault(personaId, "custom").toLowerCase(Locale.ROOT)).replaceAll("-");
        return StringUtils.hasText(sanitized) ? sanitized : "custom";
    }

    private String prefix(String sessionId) {
        return sessionId + "::";
    }

    private String customerId(String sessionId, String personaId) {
        return prefix(sessionId) + personaId;
    }

    private String personaId(String sessionId, String customerId) {
        String prefix = prefix(sessionId);
        if (customerId != null && customerId.startsWith(prefix)) {
            return customerId.substring(prefix.length());
        }
        return "custom";
    }

    private String displayName(String personaId) {
        return switch (personaId) {
            case "billing" -> "Billing Support";
            case "refund" -> "Refund Review";
            case "clean" -> "Clean Login Help";
            default -> "Custom Intake";
        };
    }

    private String originalEvidencePolicy(SupportMessage message) {
        String evidence = firstText(message.getMessageEncryptedOriginal(), message.getSubjectEncryptedOriginal());
        if (!StringUtils.hasText(evidence)) {
            return "none";
        }
        if (evidence.startsWith("HASH:")) {
            return "hash-only";
        }
        return "encrypted-original";
    }

    private String requiredText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private String valueOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private int clampLimit(int requestedLimit) {
        return Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
    }

    public record CreateSessionRequest(String sessionId) {}

    public record SubmitMessageRequest(
        @NotBlank String sessionId,
        String personaId,
        String channel,
        String subject,
        @NotBlank String message
    ) {}

    public record DemoDashboard(
        String sessionId,
        List<DemoSample> samples,
        List<PrivacyMessageCard> messages,
        PrivacyMetrics metrics,
        List<PipelineStage> pipeline
    ) {}

    public record DemoSample(
        String personaId,
        String title,
        String channel,
        String subject,
        String message,
        List<String> expectedDetections
    ) {}

    public record PrivacyMessageCard(
        long id,
        String personaId,
        String personaName,
        String channel,
        boolean piiDetected,
        String modeApplied,
        int detectionsCount,
        String detectionsSummary,
        String processedSubject,
        String processedMessage,
        String originalEvidencePolicy,
        boolean rawInputWithheld,
        Instant createdAt
    ) {}

    public record PrivacyMetrics(
        int totalMessages,
        int piiMessages,
        int safeMessages,
        int detectionsTotal,
        boolean rawInputWithheld
    ) {}

    public record PipelineStage(
        String name,
        String description,
        String owner
    ) {}

    public record MessageResult(
        PrivacyMessageCard message,
        DemoDashboard dashboard
    ) {}

    public record SearchResult(
        String sessionId,
        String processedQuery,
        boolean queryPiiDetected,
        String queryDetectionsSummary,
        int resultCount,
        List<PrivacyMessageCard> results
    ) {}
}
