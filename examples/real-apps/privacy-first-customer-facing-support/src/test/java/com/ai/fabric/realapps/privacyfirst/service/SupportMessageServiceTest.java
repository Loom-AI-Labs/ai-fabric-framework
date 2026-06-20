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

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> availableProvider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
