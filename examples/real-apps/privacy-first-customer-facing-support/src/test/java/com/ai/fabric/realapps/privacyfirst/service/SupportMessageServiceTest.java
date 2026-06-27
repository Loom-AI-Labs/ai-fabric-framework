package com.ai.fabric.realapps.privacyfirst.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.PIIDetection;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.dto.PIIMode;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.service.AICapabilityService;
import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import com.ai.fabric.realapps.privacyfirst.repo.SupportMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportMessageServiceTest {

    private final SupportMessageRepository repository = mock(SupportMessageRepository.class);
    private final PIIDetectionService piiDetectionService = mock(PIIDetectionService.class);
    private final AICapabilityService capabilityService = mock(AICapabilityService.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final SupportMessageService service = new SupportMessageService(
        repository,
        piiDetectionService,
        availableProvider(capabilityService),
        availableProvider(aiCoreService)
    );

    @Test
    void createStoresRedactedPayloadAndIndexesOnlySavedRecord() {
        when(piiDetectionService.detectAndProcess("Billing update")).thenReturn(clean("Billing update"));
        when(piiDetectionService.detectAndProcess("Email sara@example.com please")).thenReturn(redacted(
            "Email [EMAIL] please",
            "HASH:email",
            "salt-1"
        ));
        when(repository.save(any(SupportMessage.class))).thenAnswer(invocation -> {
            SupportMessage saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });

        SupportMessage saved = service.create(
            "cust-1001",
            "webchat",
            "Billing update",
            "Email sara@example.com please"
        );

        assertThat(saved.getProcessedMessage()).isEqualTo("Email [EMAIL] please");
        assertThat(saved.getProcessedMessage()).doesNotContain("sara@example.com");
        assertThat(saved.isPiiDetected()).isTrue();
        assertThat(saved.getDetectionsSummary()).contains("EMAIL:email");
        verify(capabilityService).processEntityForAI(saved, "support-message");
    }

    @Test
    void createMasksDetectedPiiBeforePersistenceEvenWhenDetectorIsDetectOnly() {
        String subject = "Billing update for sara@example.com";
        String message = "Phone is +1 (555) 123-4567";
        when(piiDetectionService.detectAndProcess(subject)).thenReturn(detectOnly(
            subject,
            "EMAIL",
            "email",
            subject.indexOf("sara@example.com"),
            subject.indexOf("sara@example.com") + "sara@example.com".length(),
            "[EMAIL]"
        ));
        when(piiDetectionService.detectAndProcess(message)).thenReturn(detectOnly(
            message,
            "PHONE",
            "phone_number",
            message.indexOf("+1 (555) 123-4567"),
            message.indexOf("+1 (555) 123-4567") + "+1 (555) 123-4567".length(),
            "[PHONE]"
        ));
        when(repository.save(any(SupportMessage.class))).thenAnswer(invocation -> {
            SupportMessage saved = invocation.getArgument(0);
            saved.setId(43L);
            return saved;
        });

        SupportMessage saved = service.create("cust-1001", "webchat", subject, message);

        assertThat(saved.getProcessedSubject()).isEqualTo("Billing update for [EMAIL]");
        assertThat(saved.getProcessedMessage()).isEqualTo("Phone is [PHONE]");
        assertThat(saved.getProcessedSubject()).doesNotContain("sara@example.com");
        assertThat(saved.getProcessedMessage()).doesNotContain("+1 (555) 123-4567");
        assertThat(saved.isPiiDetected()).isTrue();
        verify(capabilityService).processEntityForAI(saved, "support-message");
    }

    @Test
    void semanticSearchResolvesSupportMessageIdsFromAiFabricResults() {
        SupportMessage message = new SupportMessage();
        message.setId(42L);
        message.setProcessedMessage("Email [EMAIL] please");
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(Map.of("entityId", "42"), Map.of("id", "not-a-number")))
            .build());
        when(repository.findById(42L)).thenReturn(Optional.of(message));

        List<SupportMessage> results = service.semanticSearch("billing email", 5);

        assertThat(results).containsExactly(message);
    }

    @Test
    void semanticSearchMasksPiiBeforeSendingQueryToAiFabricSearch() {
        String query = "find sara@example.com billing history";
        when(piiDetectionService.detectAndProcess(query)).thenReturn(detectOnly(
            query,
            "EMAIL",
            "email",
            query.indexOf("sara@example.com"),
            query.indexOf("sara@example.com") + "sara@example.com".length(),
            "[EMAIL]"
        ));
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .build());

        service.semanticSearch(query, 5);

        org.mockito.ArgumentCaptor<AISearchRequest> requestCaptor = forClass(AISearchRequest.class);
        verify(aiCoreService).performSearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getQuery()).isEqualTo("find [EMAIL] billing history");
    }

    private static PIIDetectionResult clean(String text) {
        return PIIDetectionResult.builder()
            .processedQuery(text)
            .piiDetected(false)
            .modeApplied(PIIMode.REDACT)
            .build();
    }

    private static PIIDetectionResult redacted(String text, String encryptedOriginal, String salt) {
        return PIIDetectionResult.builder()
            .processedQuery(text)
            .piiDetected(true)
            .modeApplied(PIIMode.REDACT)
            .encryptedOriginalQuery(encryptedOriginal)
            .encryptionSalt(salt)
            .detections(List.of(PIIDetection.builder()
                .type("EMAIL")
                .fieldName("email")
                .maskedValue("[EMAIL]")
                .build()))
            .build();
    }

    private static PIIDetectionResult detectOnly(String text,
                                                 String type,
                                                 String fieldName,
                                                 int startIndex,
                                                 int endIndex,
                                                 String maskedValue) {
        return PIIDetectionResult.builder()
            .processedQuery(text)
            .piiDetected(true)
            .modeApplied(PIIMode.DETECT_ONLY)
            .detections(List.of(PIIDetection.builder()
                .type(type)
                .fieldName(fieldName)
                .startIndex(startIndex)
                .endIndex(endIndex)
                .maskedValue(maskedValue)
                .build()))
            .build();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> availableProvider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
