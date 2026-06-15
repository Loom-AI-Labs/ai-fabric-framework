package ai.fabric.intent.history;

import ai.fabric.config.IntentHistoryProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.entity.IntentHistory;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.repository.IntentHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.fabric.testsupport.SimplePIIDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHistoryServiceTest {

    private IntentHistoryRepository repository;
    private IntentHistoryService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(IntentHistoryRepository.class);

        PIIDetectionService detectionService = new SimplePIIDetectionService();
        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> piiProvider = Mockito.mock(ObjectProvider.class);
        when(piiProvider.getIfAvailable()).thenReturn(detectionService);

        IntentHistoryProperties historyProperties = new IntentHistoryProperties();
        historyProperties.setEnabled(true);
        historyProperties.setRetentionDays(60);
        historyProperties.setStoreEncryptedQuery(false);

        ObjectMapper objectMapper = new ObjectMapper();

        service = new IntentHistoryService(repository, piiProvider, objectMapper, historyProperties);
        when(repository.save(any(IntentHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldPersistRedactedQueryAndSanitizedPayload() {
        MultiIntentResponse intents = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().intent("cancel_subscription").build()))
            .build();

        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("Processed successfully.")
            .data(Map.of(
                "actionResult", ActionResult.builder()
                    .success(true)
                    .message("Card processed 4111-1111-1111-1111")
                    .build()
            ))
            .build();
        result.setSanitizedPayload(Map.of("message", "Processed successfully."));

        Optional<IntentHistory> saved = service.recordIntent(
            "user-123",
            "session-1",
            "Email me at user@example.com",
            intents,
            result
        );

        assertThat(saved).isPresent();

        ArgumentCaptor<IntentHistory> captor = ArgumentCaptor.forClass(IntentHistory.class);
        verify(repository).save(captor.capture());
        IntentHistory history = captor.getValue();

        assertThat(history.getRedactedQuery()).doesNotContain("user@example.com");
        assertThat(history.getRedactedQuery()).contains("***@***.***");
        assertThat(history.getResultJson()).contains("Processed successfully.");
        assertThat(history.getIntentCount()).isEqualTo(1);
        assertThat(history.getExpiresAt()).isNotNull();
    }

    @Test
    void shouldSkipPersistenceWhenDisabled() {
        IntentHistoryProperties disabledProps = new IntentHistoryProperties();
        disabledProps.setEnabled(false);

        IntentHistoryService disabledService = new IntentHistoryService(
            repository,
            piiProviderWith(new SimplePIIDetectionService()),
            new ObjectMapper(),
            disabledProps
        );

        Optional<IntentHistory> result = disabledService.recordIntent(
            "user-123",
            "session-1",
            "Just a query",
            null,
            OrchestrationResult.builder().success(true).build()
        );

        assertThat(result).isEmpty();
        verify(repository, never()).save(any(IntentHistory.class));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PIIDetectionService> piiProviderWith(PIIDetectionService detectionService) {
        ObjectProvider<PIIDetectionService> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(detectionService);
        return provider;
    }
}
