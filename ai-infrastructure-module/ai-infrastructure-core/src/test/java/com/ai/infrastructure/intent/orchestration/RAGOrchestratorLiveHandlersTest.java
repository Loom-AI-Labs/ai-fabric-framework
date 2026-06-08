package com.ai.infrastructure.intent.orchestration;

import com.ai.infrastructure.access.AIAccessControlService;
import com.ai.infrastructure.config.TestConfiguration;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.IntentQueryExtractor;
import com.ai.infrastructure.intent.extraction.IntentExtractionInput;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.security.AISecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.intent-extraction.progressive.enabled=false",
    "ai.actions.builtin.vector-management.enabled=true"
})
@Transactional
class RAGOrchestratorLiveHandlersTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private VectorDatabaseService vectorDatabaseService;

    @MockBean
    private IntentQueryExtractor intentQueryExtractor;

    @MockBean
    private AISecurityService securityService;

    @MockBean
    private AIAccessControlService accessControlService;

    @BeforeEach
    void cleanIndex() {
        vectorDatabaseService.clearVectors();
        when(securityService.analyzeRequest(any())).thenReturn(
            com.ai.infrastructure.dto.AISecurityResponse.builder()
                .shouldBlock(false)
                .accessAllowed(true)
                .success(true)
                .build()
        );
        when(accessControlService.checkAccess(any())).thenReturn(
            com.ai.infrastructure.dto.AIAccessControlResponse.builder()
                .accessGranted(true)
                .success(true)
                .build()
        );
    }

    @Test
    void orchestratorShouldExecuteClearVectorAction() {
        vectorDatabaseService.storeVector(
            "doc",
            "doc-1",
            "sample content",
            List.of(0.1, 0.2, 0.3),
            Map.of("source", "test")
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("clear_vector_index")
            .build();
        when(intentQueryExtractor.extract(
            argThat(input -> input != null && "Clear index".equals(input.userQuery())),
            any(OrchestrationContext.class)
        ))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        OrchestrationResult result = orchestrator.orchestrate("Clear index", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("test-user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
        assertThat(actionResult).isNotNull();
        assertThat(actionResult.isSuccess()).isTrue();
        assertThat(actionResult.getData()).isNotNull();
        Map<String, Object> actionData = actionResult.getData().toMap();
        assertThat(actionData).containsEntry("removed", 1L);
        assertThat(vectorDatabaseService.getVectorCountByEntityType("doc")).isZero();
        assertThat(result.getSanitizedPayload()).isNotEmpty();
    }

    @Test
    void orchestratorShouldExecuteRemoveVectorAction() {
        String entityType = "doc";
        String entityId = "doc-2";
        vectorDatabaseService.storeVector(
            entityType,
            entityId,
            "sample content",
            List.of(0.4, 0.5, 0.6),
            Map.of()
        );

        String query = "Remove vector entityType=" + entityType + " entityId=" + entityId;

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("remove_vector")
            .actionParams(Map.of("entityType", entityType, "entityId", entityId))
            .build();
        when(intentQueryExtractor.extract(
            argThat(input -> input != null && query.equals(input.userQuery())),
            any(OrchestrationContext.class)
        ))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        OrchestrationResult result = orchestrator.orchestrate(query, com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("test-user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
        assertThat(actionResult).isNotNull();
        assertThat(actionResult.isSuccess()).isTrue();
        assertThat(actionResult.getData()).isNotNull();
        Map<String, Object> actionData = actionResult.getData().toMap();
        assertThat(actionData).containsEntry("removed", true);
        assertThat(vectorDatabaseService.vectorExists(entityType, entityId)).isFalse();
        assertThat(result.getSanitizedPayload()).isNotEmpty();
    }
}
