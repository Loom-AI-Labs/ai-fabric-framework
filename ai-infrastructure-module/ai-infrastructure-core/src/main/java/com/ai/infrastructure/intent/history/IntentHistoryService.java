package com.ai.infrastructure.intent.history;

import com.ai.infrastructure.config.IntentHistoryProperties;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.dto.PIIDetection;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.entity.IntentHistory;
import com.ai.infrastructure.privacy.pii.PIIDetectionService;
import com.ai.infrastructure.dto.PIIDetectionResult;
import com.ai.infrastructure.repository.IntentHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persist structured intent executions for analytics and compliance.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IntentHistoryService {

    private final IntentHistoryRepository repository;
    private final ObjectProvider<PIIDetectionService> piiDetectionService;
    private final ObjectMapper objectMapper;
    private final IntentHistoryProperties properties;

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Transactional
    public Optional<IntentHistory> recordIntent(String userId,
                                                String sessionId,
                                                String originalQuery,
                                                MultiIntentResponse intents,
                                                OrchestrationResult result) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }

        if (!StringUtils.hasText(userId)) {
            log.debug("Skipping intent history persistence because userId is blank.");
            return Optional.empty();
        }

        try {
            String sanitizedQuery = sanitizeQuery(originalQuery);
            String encryptedQuery = determineEncryptedPayload(originalQuery);
            String intentsJson = serializeIntents(intents);
            String resultJson = serializeResult(result.getSanitizedPayload());
            String metadataJson = serializeResult(result.getMetadata());

            IntentHistory history = IntentHistory.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .sessionId(StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString())
                .redactedQuery(sanitizedQuery)
                .encryptedQuery(encryptedQuery)
                .intentsJson(intentsJson)
                .resultJson(resultJson)
                .metadataJson(metadataJson)
                .executionStatus(result.getType() != null ? result.getType().name() : null)
                .success(result.isSuccess())
                .hasSensitiveData(Boolean.valueOf(hasSensitiveData(originalQuery)))
                .sensitiveDataTypes(resolveSensitiveTypes(originalQuery))
                .intentCount(intents != null && intents.getIntents() != null ? intents.getIntents().size() : 0)
                .expiresAt(calculateExpiry())
                .build();

            IntentHistory saved = repository.save(history);
            log.debug("Persisted intent history record id={} user={}", saved.getId(), userId);
            return Optional.of(saved);
        } catch (Exception ex) {
            log.warn("Unable to persist intent history for user {}: {}", userId, ex.getMessage());
            return Optional.empty();
        }
    }

    public List<IntentHistory> getUserIntentHistory(String userId, int limit) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .limit(Math.max(limit, 0))
            .collect(Collectors.toList());
    }

    public List<IntentHistory> getUserIntentHistoryBetween(String userId,
                                                           LocalDateTime start,
                                                           LocalDateTime end) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        return repository.findByUserIdAndCreatedAtBetween(userId, start, end);
    }

    @Scheduled(cron = "${ai.intent-history.cleanup-cron:0 0 * * * *}")
    public void cleanupExpiredHistory() {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long removed = repository.deleteByExpiresAtBefore(now);
        if (removed > 0) {
            log.info("Intent history cleanup removed {} record(s).", removed);
        }
    }

    private String sanitizeQuery(String originalQuery) {
        if (!StringUtils.hasText(originalQuery)) {
            return originalQuery;
        }
        PIIDetectionService detectionService = piiDetectionService.getIfAvailable();
        if (detectionService == null) {
            return originalQuery;
        }

        PIIDetectionResult analysis = detectionService.analyze(originalQuery);
        if (!analysis.isPiiDetected()) {
            return originalQuery;
        }
        String sanitized = redact(originalQuery, analysis.getDetections());
        if (sanitized.equals(originalQuery)) {
            // Fallback in case masking was not provided; ensure redaction regardless.
            sanitized = sanitizeByMasking(originalQuery);
        }
        return sanitized;
    }

    private boolean hasSensitiveData(String originalQuery) {
        if (!StringUtils.hasText(originalQuery)) {
            return false;
        }
        PIIDetectionService detectionService = piiDetectionService.getIfAvailable();
        if (detectionService == null) {
            return false;
        }
        return detectionService.analyze(originalQuery).isPiiDetected();
    }

    private String resolveSensitiveTypes(String originalQuery) {
        if (!StringUtils.hasText(originalQuery)) {
            return null;
        }
        PIIDetectionService detectionService = piiDetectionService.getIfAvailable();
        if (detectionService == null) {
            return null;
        }

        List<String> types = detectionService.analyze(originalQuery).getDetections().stream()
            .map(PIIDetection::getType)
            .filter(StringUtils::hasText)
            .distinct()
            .collect(Collectors.toList());
        return types.isEmpty() ? null : String.join(",", types);
    }

    private String determineEncryptedPayload(String originalQuery) {
        if (!properties.isStoreEncryptedQuery() || !StringUtils.hasText(originalQuery)) {
            return null;
        }
        PIIDetectionService detectionService = piiDetectionService.getIfAvailable();
        if (detectionService == null) {
            return null;
        }

        PIIDetectionResult processed = detectionService.detectAndProcess(originalQuery);
        return processed.getEncryptedOriginalQuery();
    }

    private String serializeIntents(MultiIntentResponse intents) throws JsonProcessingException {
        if (intents == null) {
            return null;
        }
        intents.normalize();
        return objectMapper.writeValueAsString(intents);
    }

    private String serializeResult(Object payload) throws JsonProcessingException {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String str) {
            return str;
        }
        return objectMapper.writeValueAsString(payload);
    }

    private LocalDateTime calculateExpiry() {
        int retention = Math.max(1, properties.getRetentionDays());
        return LocalDateTime.now().plusDays(retention);
    }

    private String sanitizeByMasking(String original) {
        PIIDetectionService detectionService = piiDetectionService.getIfAvailable();
        if (detectionService == null) {
            return original;
        }

        PIIDetectionResult analysis = detectionService.detectAndProcess(original);
        if (analysis.isPiiDetected()) {
            return analysis.getProcessedQuery();
        }
        return original;
    }

    private String redact(String original, List<PIIDetection> detections) {
        if (CollectionUtils.isEmpty(detections)) {
            return original;
        }
        StringBuilder builder = new StringBuilder(original);
        detections.stream()
            .filter(d -> d.getMaskedValue() != null)
            .sorted((a, b) -> Integer.compare(b.getStartIndex(), a.getStartIndex()))
            .forEach(detection -> {
                int start = Math.max(0, Math.min(detection.getStartIndex(), builder.length()));
                int end = Math.max(start, Math.min(detection.getEndIndex(), builder.length()));
                builder.replace(start, end, detection.getMaskedValue());
            });
        return builder.toString();
    }
}
