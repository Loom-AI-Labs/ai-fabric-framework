package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.dto.AIAccessControlRequest;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccessControlStep}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessControlStep")
class AccessControlStepTest {
    
    @Mock
    private AIAccessControlService accessControlService;
    
    @Captor
    private ArgumentCaptor<AIAccessControlRequest> requestCaptor;
    
    private AccessControlStep step;
    
    @BeforeEach
    void setUp() {
        step = new AccessControlStep(accessControlService);
    }
    
    @Nested
    @DisplayName("metadata")
    class Metadata {
        
        @Test
        @DisplayName("Should have correct step name")
        void shouldHaveCorrectStepName() {
            assertThat(step.getStepName()).isEqualTo("AccessControl");
        }
        
        @Test
        @DisplayName("Should have correct order (after security)")
        void shouldHaveCorrectOrder() {
            assertThat(step.getOrder()).isEqualTo(20);
        }
    }
    
    @Nested
    @DisplayName("process()")
    class ProcessMethod {
        
        @Test
        @DisplayName("Should pass through when access granted")
        void shouldPassThroughWhenAccessGranted() {
            // Arrange
            when(accessControlService.checkAccess(any()))
                .thenReturn(AIAccessControlResponse.builder()
                    .accessGranted(true)
                    .build());
            
            PipelineContext context = PipelineContext.from(
                "Find orders",
                OrchestrationContext.forUser("user-123")
            );
            
            // Act
            PipelineContext result = step.process(context);
            
            // Assert
            assertThat(result.isShouldTerminate()).isFalse();
        }
        
        @Test
        @DisplayName("Should terminate when access denied (fail-closed)")
        void shouldTerminateWhenAccessDenied() {
            // Arrange
            when(accessControlService.checkAccess(any()))
                .thenReturn(AIAccessControlResponse.builder()
                    .accessGranted(false)
                    .build());
            
            PipelineContext context = PipelineContext.from(
                "Find orders",
                OrchestrationContext.forUser("user-123")
            );
            
            // Act
            PipelineContext result = step.process(context);
            
            // Assert - Fail-closed: entire request denied
            assertThat(result.isShouldTerminate()).isTrue();
            assertThat(result.getEarlyTerminationResult()).isNotNull();
            assertThat(result.getEarlyTerminationResult().isSuccess()).isFalse();
            assertThat(result.getEarlyTerminationResult().getMessage())
                .isEqualTo("Access denied by policy.");
        }
        
        @Test
        @DisplayName("Should request access to rag:intent resource")
        void shouldRequestAccessToRagIntentResource() {
            // Arrange
            when(accessControlService.checkAccess(any()))
                .thenReturn(AIAccessControlResponse.builder()
                    .accessGranted(true)
                    .build());
            
            PipelineContext context = PipelineContext.from(
                "Query",
                OrchestrationContext.forUser("user-123")
            );
            
            // Act
            step.process(context);
            
            // Assert
            verify(accessControlService).checkAccess(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getResourceId()).isEqualTo("rag:intent");
            assertThat(requestCaptor.getValue().getOperationType()).isEqualTo("READ");
            assertThat(requestCaptor.getValue().getAuthContext().getSubjectId()).isEqualTo("user-123");
            assertThat(requestCaptor.getValue().getAuthContext().getSubjectType()).isEqualTo("END_USER");
        }
        
        @Test
        @DisplayName("Should include RAG_ORCHESTRATOR entry point in metadata")
        void shouldIncludeEntryPointInMetadata() {
            // Arrange
            when(accessControlService.checkAccess(any()))
                .thenReturn(AIAccessControlResponse.builder()
                    .accessGranted(true)
                    .build());
            
            PipelineContext context = PipelineContext.from(
                "Query",
                OrchestrationContext.forUser("user-123")
            );
            
            // Act
            step.process(context);
            
            // Assert
            verify(accessControlService).checkAccess(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getMetadata())
                .containsEntry("entryPoint", "RAG_ORCHESTRATOR");
        }

        @Test
        @DisplayName("Should preserve verified auth metadata for downstream policy checks")
        void shouldPreserveVerifiedAuthMetadataForDownstreamPolicyChecks() {
            when(accessControlService.checkAccess(any()))
                .thenReturn(AIAccessControlResponse.builder()
                    .accessGranted(true)
                    .build());

            OrchestrationContext orchestrationContext = OrchestrationContext.builder()
                .sessionId("anon-session-1")
                .metadata(new java.util.LinkedHashMap<>(Map.ofEntries(
                    entry(OrchestrationContextMetadataKeys.SUBJECT_ID, "anon-session-1"),
                    entry(OrchestrationContextMetadataKeys.SUBJECT_TYPE, "ANONYMOUS_SESSION"),
                    entry(OrchestrationContextMetadataKeys.AUTH_MODE, "PUBLIC_RUNTIME_ANONYMOUS"),
                    entry(OrchestrationContextMetadataKeys.CALLER_TYPE, "PUBLIC_BROWSER"),
                    entry(OrchestrationContextMetadataKeys.DEPLOYMENT_ID, "dep-123"),
                    entry(OrchestrationContextMetadataKeys.CUSTOMER_ID, "cus-123"),
                    entry(OrchestrationContextMetadataKeys.TENANT_ID, "ten-123"),
                    entry(OrchestrationContextMetadataKeys.GRANTED_SCOPES, java.util.List.of("chat:read")),
                    entry(OrchestrationContextMetadataKeys.REQUESTED_SCOPES, java.util.List.of("chat:query"))
                )))
                .build();

            PipelineContext context = PipelineContext.from("Anonymous question", orchestrationContext);

            step.process(context);

            verify(accessControlService).checkAccess(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getMetadata())
                .containsEntry(OrchestrationContextMetadataKeys.SUBJECT_ID, "anon-session-1")
                .containsEntry(OrchestrationContextMetadataKeys.SUBJECT_TYPE, "ANONYMOUS_SESSION")
                .containsEntry(OrchestrationContextMetadataKeys.AUTH_MODE, "PUBLIC_RUNTIME_ANONYMOUS")
                .containsEntry(OrchestrationContextMetadataKeys.CALLER_TYPE, "PUBLIC_BROWSER")
                .containsEntry(OrchestrationContextMetadataKeys.DEPLOYMENT_ID, "dep-123")
                .containsEntry(OrchestrationContextMetadataKeys.CUSTOMER_ID, "cus-123")
                .containsEntry(OrchestrationContextMetadataKeys.TENANT_ID, "ten-123");
            assertThat(requestCaptor.getValue().getMetadata().get(OrchestrationContextMetadataKeys.GRANTED_SCOPES))
                .isEqualTo(java.util.List.of("chat:read"));
            assertThat(requestCaptor.getValue().getMetadata().get(OrchestrationContextMetadataKeys.REQUESTED_SCOPES))
                .isEqualTo(java.util.List.of("chat:query"));
        }
        
        @Test
        @DisplayName("Should handle null accessGranted as denied")
        void shouldHandleNullAccessGrantedAsDenied() {
            // Arrange
            when(accessControlService.checkAccess(any()))
                .thenReturn(AIAccessControlResponse.builder()
                    .accessGranted(null) // Null should be treated as denied
                    .build());
            
            PipelineContext context = PipelineContext.from(
                "Query",
                OrchestrationContext.forUser("user-123")
            );
            
            // Act
            PipelineContext result = step.process(context);
            
            // Assert - Fail-closed: null treated as denied
            assertThat(result.isShouldTerminate()).isTrue();
        }
    }
}
