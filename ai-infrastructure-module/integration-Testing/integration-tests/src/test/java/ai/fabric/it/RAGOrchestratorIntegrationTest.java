package ai.fabric.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.compliance.AIComplianceService;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.dto.AIComplianceResponse;
import ai.fabric.dto.AISecurityResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.spi.RAGProvider;
import ai.fabric.security.AISecurityService;
import ai.fabric.security.ResponseSanitizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.pii-detection.enabled=false",
    "ai.intent-extraction.progressive.enabled=false",
    "ai.governance.enabled=true",
    "ai.governance.compliance.enabled=true",
    "ai.smart-suggestions.enabled=false",
    "spring.task.scheduling.enabled=false"
})
class RAGOrchestratorIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @MockitoBean
    private AISecurityService securityService;

    @MockitoBean
    private AIAccessControlService accessControlService;

    @MockitoBean
    private AIComplianceService complianceService;

    @MockitoBean
    private IntentQueryExtractor intentQueryExtractor;

    @MockitoBean
    private AIActionRegistry actionHandlerRegistry;

    @MockitoBean(name = "ragService")
    private RAGProvider ragProvider;

    @MockitoBean
    private ResponseSanitizer responseSanitizer;

    private AIActionHandler actionHandler;

    @BeforeEach
    void setupDefaults() {
        actionHandler = mock(AIActionHandler.class);

        when(securityService.analyzeRequest(any())).thenReturn(
            AISecurityResponse.builder()
                .requestId("sec")
                .subjectId("user")
                .threatsDetected(List.of())
                .securityScore(100.0)
                .accessAllowed(true)
                .rateLimitExceeded(false)
                .shouldBlock(false)
                .success(true)
                .build()
        );

        when(accessControlService.checkAccess(any())).thenReturn(
            AIAccessControlResponse.builder()
                .accessGranted(true)
                .fromCache(false)
                .success(true)
                .build()
        );

        when(complianceService.checkCompliance(any())).thenReturn(
            AIComplianceResponse.builder()
                .overallCompliant(true)
                .success(true)
                .build()
        );

        Intent informationIntent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("find_data")
            .vectorSpace("product")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .build();

        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(
            MultiIntentResponse.builder()
                .intents(List.of(informationIntent))
                .build()
        );

        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .context("ok")
                .documents(List.of())
                .success(true)
                .build()
        );

        when(responseSanitizer.sanitize(any(), any())).thenReturn(Map.of());
        when(actionHandlerRegistry.findHandler(any())).thenReturn(java.util.Optional.of(actionHandler));
        when(actionHandler.validateActionAllowed(any())).thenReturn(true);
        when(actionHandler.executeAction(any(), any())).thenReturn(
            ActionResult.builder()
                .success(true)
                .message("done")
                .build()
        );
        when(actionHandler.getConfirmationMessage(any(), any())).thenReturn("confirmed");
    }

    @Test
    void hooksInvokedInOrder() {
        OrchestrationResult result = orchestrator.orchestrate("hello world", ai.fabric.intent.orchestration.OrchestrationContext.forUser("user"));

        assertTrue(result.isSuccess());

        InOrder order = inOrder(securityService, accessControlService, complianceService, ragProvider);
        order.verify(securityService).analyzeRequest(any());
        order.verify(accessControlService).checkAccess(any());
        order.verify(complianceService).checkCompliance(any());
        order.verify(ragProvider).performRag(any());
    }

    @Test
    void securityFailureShortCircuitsFlow() {
        when(securityService.analyzeRequest(any())).thenReturn(
            AISecurityResponse.builder()
                .requestId("sec")
                .subjectId("user")
                .threatsDetected(List.of("INJECTION_ATTACK"))
                .securityScore(10.0)
                .accessAllowed(false)
                .rateLimitExceeded(false)
                .shouldBlock(true)
                .success(true)
                .build()
        );

        OrchestrationResult result = orchestrator.orchestrate("malicious", ai.fabric.intent.orchestration.OrchestrationContext.forUser("user"));

        assertFalse(result.isSuccess());
        InOrder order = inOrder(securityService, accessControlService, complianceService, ragProvider);
        order.verify(securityService).analyzeRequest(any());
        order.verify(accessControlService, never()).checkAccess(any());
        order.verify(complianceService, never()).checkCompliance(any());
        order.verify(ragProvider, never()).performRag(any());
    }

    @Test
    void complianceFailureStopsRagExecution() {
        when(complianceService.checkCompliance(any())).thenReturn(
            AIComplianceResponse.builder()
                .overallCompliant(false)
                .violations(List.of("GDPR"))
                .success(true)
                .build()
        );

        OrchestrationResult result = orchestrator.orchestrate("hello world", ai.fabric.intent.orchestration.OrchestrationContext.forUser("user"));

        assertFalse(result.isSuccess());
        InOrder order = inOrder(securityService, accessControlService, complianceService, ragProvider);
        order.verify(securityService).analyzeRequest(any());
        order.verify(accessControlService).checkAccess(any());
        order.verify(complianceService).checkCompliance(any());
        order.verify(ragProvider, never()).performRag(any());
    }
}
