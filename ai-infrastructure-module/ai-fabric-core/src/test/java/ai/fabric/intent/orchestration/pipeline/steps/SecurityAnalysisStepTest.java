package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.AISecurityRequest;
import ai.fabric.dto.AISecurityResponse;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.security.AISecurityService;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.of;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SecurityAnalysisStep}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityAnalysisStep")
class SecurityAnalysisStepTest {
    
    @Mock
    private AISecurityService securityService;
    
    @Captor
    private ArgumentCaptor<AISecurityRequest> requestCaptor;
    
    private SecurityAnalysisStep step;
    
    @BeforeEach
    void setUp() {
        step = new SecurityAnalysisStep(securityService);
    }
    
    @Nested
    @DisplayName("metadata")
    class Metadata {
        
        @Test
        @DisplayName("Should have correct step name")
        void shouldHaveCorrectStepName() {
            assertThat(step.getStepName()).isEqualTo("SecurityAnalysis");
        }
        
        @Test
        @DisplayName("Should have correct order (first step)")
        void shouldHaveCorrectOrder() {
            assertThat(step.getOrder()).isEqualTo(10);
        }
    }
    
    @Nested
    @DisplayName("process()")
    class ProcessMethod {
        
        @Test
        @DisplayName("Should pass through when security allows")
        void shouldPassThroughWhenSecurityAllows() {
            // Arrange
            when(securityService.analyzeRequest(any()))
                .thenReturn(AISecurityResponse.builder()
                    .shouldBlock(false)
                    .build());
            
            PipelineContext context = PipelineContext.from(
                "Find my orders",
                OrchestrationContext.forUser("user-123")
            );
            
            // Act
            PipelineContext result = step.process(context);
            
            // Assert
            assertThat(result.isShouldTerminate()).isFalse();
            assertThat(result.getOriginalQuery()).isEqualTo("Find my orders");
        }
        
        @Test
        @DisplayName("Should terminate when security blocks")
        void shouldTerminateWhenSecurityBlocks() {
            // Arrange
            when(securityService.analyzeRequest(any()))
                .thenReturn(AISecurityResponse.builder()
                    .shouldBlock(true)
                    .build());
            
            PipelineContext context = PipelineContext.from(
                "Malicious query",
                OrchestrationContext.forUser("user-123")
            );
            
            // Act
            PipelineContext result = step.process(context);
            
            // Assert
            assertThat(result.isShouldTerminate()).isTrue();
            assertThat(result.getEarlyTerminationResult()).isNotNull();
            assertThat(result.getEarlyTerminationResult().isSuccess()).isFalse();
            assertThat(result.getEarlyTerminationResult().getMessage())
                .isEqualTo("Request blocked by security controls.");
        }
        
        @Test
        @DisplayName("Should pass correct data to security service")
        void shouldPassCorrectDataToSecurityService() {
            // Arrange
            when(securityService.analyzeRequest(any()))
                .thenReturn(AISecurityResponse.builder()
                    .shouldBlock(false)
                    .build());
            
            OrchestrationContext orchContext = OrchestrationContext.builder()
                .sessionId("session-456")
                .ipAddress("192.168.1.1")
                .userAgent("TestAgent/1.0")
                .metadata(of(
                    OrchestrationContextMetadataKeys.SUBJECT_ID, "user-123",
                    OrchestrationContextMetadataKeys.SUBJECT_TYPE, "END_USER",
                    OrchestrationContextMetadataKeys.AUTH_MODE, "PUBLIC_RUNTIME_BROWSER_TOKEN",
                    OrchestrationContextMetadataKeys.CALLER_TYPE, "END_USER_BROWSER"
                ))
                .build();
            
            PipelineContext context = PipelineContext.from("Test query", orchContext);
            
            // Act
            step.process(context);
            
            // Assert
            verify(securityService).analyzeRequest(requestCaptor.capture());
            AISecurityRequest request = requestCaptor.getValue();
            
            assertThat(request.getAuthContext()).isNotNull();
            assertThat(request.getAuthContext().getSubjectId()).isEqualTo("user-123");
            assertThat(request.getAuthContext().getSessionId()).isEqualTo("session-456");
            assertThat(request.getContent()).isEqualTo("Test query");
            assertThat(request.getOperationType()).isEqualTo("INTENT_QUERY");
            assertThat(request.getIpAddress()).isEqualTo("192.168.1.1");
            assertThat(request.getUserAgent()).isEqualTo("TestAgent/1.0");
        }
        
        @Test
        @DisplayName("Should include metadata for authenticated users")
        void shouldIncludeMetadataForAuthenticatedUsers() {
            // Arrange
            when(securityService.analyzeRequest(any()))
                .thenReturn(AISecurityResponse.builder()
                    .shouldBlock(false)
                    .build());
            
            OrchestrationContext orchContext = OrchestrationContext.builder()
                .userId("user-123")
                .sessionId("session-456")
                .build();
            
            PipelineContext context = PipelineContext.from("Query", orchContext);
            
            // Act
            step.process(context);
            
            // Assert
            verify(securityService).analyzeRequest(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getMetadata())
                .containsEntry("authenticated", true)
                .containsEntry("sessionId", "session-456");
        }
        
        @Test
        @DisplayName("Should include metadata for anonymous users")
        void shouldIncludeMetadataForAnonymousUsers() {
            // Arrange
            when(securityService.analyzeRequest(any()))
                .thenReturn(AISecurityResponse.builder()
                    .shouldBlock(false)
                    .build());
            
            PipelineContext context = PipelineContext.from(
                "Query",
                OrchestrationContext.forSession("session-789")
            );
            
            // Act
            step.process(context);
            
            // Assert
            verify(securityService).analyzeRequest(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getMetadata())
                .containsEntry("authenticated", false)
                .containsEntry("sessionId", "session-789");
        }

        @Test
        @DisplayName("Should use server-owned identity for trusted application execution")
        void shouldUseServerOwnedIdentityForTrustedApplicationExecution() {
            when(securityService.analyzeRequest(any()))
                .thenReturn(AISecurityResponse.builder()
                    .shouldBlock(false)
                    .build());
            TrustedExecutionContext trusted = new TrustedExecutionContext(
                new ExecutionPrincipal(
                    "account-service",
                    ExecutionPrincipalType.SERVICE
                ),
                new ExecutionSubjectRef("account", "account-42"),
                ExecutionSource.APPLICATION,
                "tenant-1",
                "deployment-1",
                Set.of("specialist:account-resolver@1"),
                "correlation-1",
                Instant.parse("2026-07-28T10:00:00Z")
            );
            PipelineContext context = PipelineContext.from(new OrchestrationRequest(
                "Inspect the account",
                OrchestrationContext.builder().build(),
                trusted,
                ConversationPersistencePolicy.NEVER
            ));

            step.process(context);

            verify(securityService).analyzeRequest(requestCaptor.capture());
            AISecurityRequest request = requestCaptor.getValue();
            assertThat(request.getAuthContext().getSubjectId())
                .isEqualTo("account-42");
            assertThat(request.getAuthContext().getSubjectType())
                .isEqualTo("account");
            assertThat(request.getAuthContext().getTenantId())
                .isEqualTo("tenant-1");
            assertThat(request.getMetadata())
                .containsEntry("authenticated", true)
                .containsEntry("executionSource", "APPLICATION")
                .containsEntry("principalType", "SERVICE")
                .containsEntry("correlationId", "correlation-1");
        }
    }
}
