package com.ai.fabric.realapps.privacyfirst.service;

import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import com.ai.fabric.realapps.privacyfirst.repo.SupportMessageRepository;
import ai.fabric.dto.PIIDetection;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.privacy.pii.PIIDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SupportMessageService {

    private final SupportMessageRepository repository;
    private final PIIDetectionService piiDetectionService;

    @Transactional
    public SupportMessage create(String customerId, String channel, String subject, String message) {
        PIIDetectionResult subjectResult = piiDetectionService.detectAndProcess(subject);
        PIIDetectionResult messageResult = piiDetectionService.detectAndProcess(message);

        SupportMessage record = new SupportMessage();
        record.setCustomerId(customerId);
        record.setChannel(channel);
        record.setProcessedSubject(subjectResult.getProcessedQuery());
        record.setProcessedMessage(messageResult.getProcessedQuery());
        record.setPiiDetected(subjectResult.isPiiDetected() || messageResult.isPiiDetected());
        record.setModeApplied(Objects.toString(messageResult.getModeApplied(), Objects.toString(subjectResult.getModeApplied(), null)));

        List<PIIDetection> combinedDetections = combineDetections(subjectResult.getDetections(), messageResult.getDetections());
        record.setDetectionsCount(combinedDetections.size());
        record.setDetectionsSummary(summarizeDetections(combinedDetections));

        record.setSubjectEncryptedOriginal(subjectResult.getEncryptedOriginalQuery());
        record.setSubjectEncryptionSalt(subjectResult.getEncryptionSalt());
        record.setMessageEncryptedOriginal(messageResult.getEncryptedOriginalQuery());
        record.setMessageEncryptionSalt(messageResult.getEncryptionSalt());

        return repository.save(record);
    }

    public List<SupportMessage> list() {
        return repository.findAll();
    }

    public SupportMessage get(long id) {
        return repository.findById(id).orElseThrow();
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
}
