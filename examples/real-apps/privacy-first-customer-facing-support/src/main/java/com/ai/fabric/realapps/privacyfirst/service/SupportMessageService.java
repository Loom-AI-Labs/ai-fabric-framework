package com.ai.fabric.realapps.privacyfirst.service;

import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import com.ai.fabric.realapps.privacyfirst.repo.SupportMessageRepository;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.PIIDetection;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupportMessageService {

    private static final String ENTITY_TYPE = "support-message";

    private final SupportMessageRepository repository;
    private final PIIDetectionService piiDetectionService;
    private final ObjectProvider<AIEntityIndexingGateway> indexingGatewayProvider;
    private final ObjectProvider<AICoreService> aiCoreServiceProvider;

    @Transactional
    public SupportMessage create(String customerId, String channel, String subject, String message) {
        PIIDetectionResult subjectResult = piiDetectionService.detectAndProcess(subject);
        PIIDetectionResult messageResult = piiDetectionService.detectAndProcess(message);

        SupportMessage record = new SupportMessage();
        record.setCustomerId(customerId);
        record.setChannel(channel);
        record.setProcessedSubject(safeProcessedText(subject, subjectResult));
        record.setProcessedMessage(safeProcessedText(message, messageResult));
        record.setPiiDetected(subjectResult.isPiiDetected() || messageResult.isPiiDetected());
        record.setModeApplied(Objects.toString(messageResult.getModeApplied(), Objects.toString(subjectResult.getModeApplied(), null)));

        List<PIIDetection> combinedDetections = combineDetections(subjectResult.getDetections(), messageResult.getDetections());
        record.setDetectionsCount(combinedDetections.size());
        record.setDetectionsSummary(summarizeDetections(combinedDetections));

        record.setSubjectEncryptedOriginal(subjectResult.getEncryptedOriginalQuery());
        record.setSubjectEncryptionSalt(subjectResult.getEncryptionSalt());
        record.setMessageEncryptedOriginal(messageResult.getEncryptedOriginalQuery());
        record.setMessageEncryptionSalt(messageResult.getEncryptionSalt());

        SupportMessage saved = repository.save(record);
        indexSafeRecord(saved);
        return saved;
    }

    public List<SupportMessage> list() {
        return repository.findAll();
    }

    public SupportMessage get(long id) {
        return repository.findById(id).orElseThrow();
    }

    public List<SupportMessage> findByCustomerId(String customerId) {
        return repository.findByCustomerId(customerId);
    }

    public List<SupportMessage> findByCustomerIdPrefix(String customerIdPrefix) {
        return repository.findByCustomerIdStartingWithOrderByCreatedAtDesc(customerIdPrefix);
    }

    public long countByCustomerIdPrefix(String customerIdPrefix) {
        return repository.countByCustomerIdStartingWith(customerIdPrefix);
    }

    @Transactional
    public long deleteByCustomerIdPrefix(String customerIdPrefix) {
        AIEntityIndexingGateway gateway = indexingGatewayProvider.getIfAvailable();
        if (gateway != null) {
            repository.findByCustomerIdStartingWithOrderByCreatedAtDesc(
                customerIdPrefix
            ).forEach(gateway::delete);
        }
        return repository.deleteByCustomerIdStartingWith(customerIdPrefix);
    }

    public PrivacyProcessingEvidence processForPrivacy(String text) {
        PIIDetectionResult result = piiDetectionService.detectAndProcess(text);
        List<PIIDetection> detections = result != null && result.getDetections() != null
            ? result.getDetections()
            : List.of();
        return new PrivacyProcessingEvidence(
            safeProcessedText(text, result),
            result != null && result.isPiiDetected(),
            detections.size(),
            summarizeDetections(detections),
            Objects.toString(result != null ? result.getModeApplied() : null, null)
        );
    }

    public List<SupportMessage> semanticSearch(String query, int limit) {
        AICoreService aiCoreService = aiCoreServiceProvider.getIfAvailable();
        if (aiCoreService == null) {
            throw new IllegalStateException("AICoreService is not available");
        }
        PIIDetectionResult piiResult = piiDetectionService.detectAndProcess(query);
        String safeQuery = safeProcessedText(query, piiResult);
        AISearchResponse response = aiCoreService.performSearch(AISearchRequest.builder()
            .query(safeQuery)
            .entityType(ENTITY_TYPE)
            .limit(Math.max(1, Math.min(20, limit)))
            .threshold(0.0d)
            .build());
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return List.of();
        }
        return response.getResults().stream()
            .map(this::extractEntityId)
            .flatMap(Optional::stream)
            .map(id -> repository.findById(id).orElse(null))
            .filter(Objects::nonNull)
            .toList();
    }

    private void indexSafeRecord(SupportMessage record) {
        AIEntityIndexingGateway indexingGateway = indexingGatewayProvider.getIfAvailable();
        if (indexingGateway == null) {
            log.debug("AIEntityIndexingGateway unavailable; skipping support-message indexing");
            return;
        }
        indexingGateway.upsert(record, AIProcessOperation.CREATE);
    }

    private String safeProcessedText(String original, PIIDetectionResult result) {
        if (result == null) {
            return original;
        }
        String processed = result.getProcessedQuery() != null ? result.getProcessedQuery() : original;
        List<PIIDetection> detections = result.getDetections() != null ? result.getDetections() : List.of();
        boolean hasPii = result.isPiiDetected() || !detections.isEmpty();
        if (hasPii && Objects.equals(processed, original) && !detections.isEmpty()) {
            return redact(original, detections);
        }
        return processed;
    }

    private String redact(String original, List<PIIDetection> detections) {
        if (original == null || original.isEmpty() || detections == null || detections.isEmpty()) {
            return original;
        }
        StringBuilder builder = new StringBuilder(original);
        detections.stream()
            .filter(detection -> canApply(detection, builder.length()))
            .sorted(Comparator.comparingInt((PIIDetection detection) -> detection.getStartIndex()).reversed())
            .forEach(detection -> {
                int start = Math.min(detection.getStartIndex(), builder.length());
                int end = Math.max(start, Math.min(detection.getEndIndex(), builder.length()));
                builder.replace(start, end, detection.getMaskedValue());
            });
        return builder.toString();
    }

    private boolean canApply(PIIDetection detection, int inputLength) {
        return detection != null
            && detection.getMaskedValue() != null
            && detection.getStartIndex() >= 0
            && detection.getStartIndex() < inputLength
            && detection.getEndIndex() > detection.getStartIndex();
    }

    private Optional<Long> extractEntityId(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        Object value = row.get("entityId") != null ? row.get("entityId") : row.get("id");
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.toString()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private List<PIIDetection> combineDetections(List<PIIDetection> subjectDetections, List<PIIDetection> messageDetections) {
        return List.of(subjectDetections, messageDetections).stream()
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .sorted(Comparator.comparing(PIIDetection::getFieldName, Comparator.nullsLast(String::compareToIgnoreCase)))
            .toList();
    }

    private String summarizeDetections(List<PIIDetection> detections) {
        if (detections == null || detections.isEmpty()) {
            return "";
        }
        return detections.stream()
            .map(detection -> detection.getType() + ":" + detection.getFieldName())
            .distinct()
            .collect(Collectors.joining(","));
    }

    public record PrivacyProcessingEvidence(
        String processedText,
        boolean piiDetected,
        int detectionsCount,
        String detectionsSummary,
        String modeApplied
    ) {}
}
