package ai.fabric.it;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.compliance.AIComplianceService;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.dto.AIComplianceResponse;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AISecurityResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.spi.RAGProvider;
import ai.fabric.security.AISecurityService;
import ai.fabric.security.ResponseSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.pii-detection.enabled=false",
    "ai.intent-extraction.progressive.enabled=false",
    "ai.smart-suggestions.enabled=false",
    // Disable scheduled tasks to avoid background indexing hitting missing tables in lightweight test context
    "spring.task.scheduling.enabled=false"
})
class IntentGenerationRoutingIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @MockBean
    private IntentQueryExtractor intentQueryExtractor;

    @MockBean(name = "ragService")
    private RAGProvider ragProvider;

    @MockBean
    private AISecurityService securityService;

    @MockBean
    private AIAccessControlService accessControlService;

    @MockBean
    private AIComplianceService complianceService;

    @MockBean
    private ResponseSanitizer responseSanitizer;

    @MockBean
    private AIActionRegistry actionRegistry;

    @MockBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setUp() {
        when(securityService.analyzeRequest(any())).thenReturn(
            AISecurityResponse.builder()
                .shouldBlock(false)
                .accessAllowed(true)
                .success(true)
                .build()
        );
        when(accessControlService.checkAccess(any())).thenReturn(
            AIAccessControlResponse.builder()
                .accessGranted(true)
                .success(true)
                .build()
        );
        when(complianceService.checkCompliance(any())).thenReturn(
            AIComplianceResponse.builder()
                .overallCompliant(true)
                .success(true)
                .build()
        );
        when(responseSanitizer.sanitize(any(), any())).thenReturn(Map.of("sanitization", Map.of()));
        when(actionRegistry.getAllMetadata()).thenReturn(List.of());
    }

    @Test
    void routesSearchOnlyWhenGenerationNotRequired() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("find_products")
            .vectorSpace("product")
            .optimizedQuery("Product entities with price_usd < 60 and stock_status = 'in_stock'")
            .requiresGeneration(false)
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder().context("search-only").documents(List.of()).success(true).build()
        );

        OrchestrationResult result = orchestrator.orchestrate("show me products under $60", ai.fabric.intent.orchestration.OrchestrationContext.forUser("user-1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Search completed.");

        ArgumentCaptor<RAGRequest> captor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider).performRag(captor.capture());
        assertThat(captor.getValue().getMetadata()).containsEntry("optimizedQuery", intent.getOptimizedQuery());
        verify(ragProvider, never()).performRAGQuery(any());
        verify(aiCoreService, never()).generateTextResponse(anyString(), any(LlmPurpose.class));
    }

    @Test
    void routesToGenerationWhenFlagged() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("recommend_products")
            .vectorSpace("product")
            .optimizedQuery("Product entities with sentiment = 'positive'")
            .requiresGeneration(true)
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder().context("generation-context").documents(List.of()).success(true).build()
        );
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder().content("llm-needed").build()
        );

        OrchestrationResult result = orchestrator.orchestrate("what should I buy next?", ai.fabric.intent.orchestration.OrchestrationContext.forUser("user-2"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("llm-needed");

        ArgumentCaptor<RAGRequest> captor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider).performRAGQuery(captor.capture());
        assertThat(captor.getValue().getMetadata()).containsEntry("requiresGeneration", true);
        verify(ragProvider, never()).performRag(any());
        verify(aiCoreService).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
    }
}
