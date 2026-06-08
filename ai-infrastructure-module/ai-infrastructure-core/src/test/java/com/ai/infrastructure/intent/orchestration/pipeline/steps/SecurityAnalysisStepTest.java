package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.dto.AISecurityRequest;
import com.ai.infrastructure.dto.AISecurityResponse;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationContextMetadataKeys;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.security.AISecurityService;
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
    }
}
