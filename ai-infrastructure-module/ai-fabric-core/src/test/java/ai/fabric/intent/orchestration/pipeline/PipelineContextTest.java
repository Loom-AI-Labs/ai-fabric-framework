package ai.fabric.intent.orchestration.pipeline;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PipelineContext}.
 */
@DisplayName("PipelineContext")
class PipelineContextTest {
    
    @Nested
    @DisplayName("from() factory method")
    class FromFactoryMethod {
        
        @Test
        @DisplayName("Should create context with valid query and context")
        void shouldCreateContextWithValidInputs() {
            // Arrange
            String query = "Find my orders";
            OrchestrationContext orchContext = OrchestrationContext.forUser("user-123");
            
            // Act
            PipelineContext context = PipelineContext.from(query, orchContext);
            
            // Assert
            assertThat(context.getOriginalQuery()).isEqualTo(query);
            assertThat(context.getProcessedQuery()).isEqualTo(query);
            assertThat(context.getOrchestrationContext()).isEqualTo(orchContext);
            assertThat(context.getRequestId()).isNotNull();
            assertThat(context.getRequestTimestamp()).isNotNull();
            assertThat(context.isShouldTerminate()).isFalse();
            assertThat(context.getDetectedPiiTypes()).isEmpty();
            assertThat(context.getMetadata()).isEmpty();
        }
        
        @Test
        @DisplayName("Should throw NullPointerException for null query")
        void shouldThrowForNullQuery() {
            OrchestrationContext orchContext = OrchestrationContext.forUser("user-123");
            
            assertThatThrownBy(() -> PipelineContext.from(null, orchContext))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query must not be null");
        }
        
        @Test
        @DisplayName("Should throw NullPointerException for null context")
        void shouldThrowForNullContext() {
            assertThatThrownBy(() -> PipelineContext.from("query", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context must not be null");
        }
        
        @Test
        @DisplayName("Should throw IllegalArgumentException for invalid context")
        void shouldThrowForInvalidContext() {
            // Context with neither userId nor sessionId
            OrchestrationContext invalidContext = OrchestrationContext.builder().build();
            
            assertThatThrownBy(() -> PipelineContext.from("query", invalidContext))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
    
    @Nested
    @DisplayName("terminate() method")
    class TerminateMethod {
        
        @Test
        @DisplayName("Should create terminated context with result")
        void shouldCreateTerminatedContext() {
            // Arrange
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forUser("user-123")
            );
            OrchestrationResult terminationResult = OrchestrationResult.error("Access denied");
            
            // Act
            PipelineContext terminated = context.terminate(terminationResult);
            
            // Assert
            assertThat(terminated.isShouldTerminate()).isTrue();
            assertThat(terminated.getEarlyTerminationResult()).isEqualTo(terminationResult);
            // Original data preserved
            assertThat(terminated.getOriginalQuery()).isEqualTo(context.getOriginalQuery());
            assertThat(terminated.getRequestId()).isEqualTo(context.getRequestId());
        }
        
        @Test
        @DisplayName("Should throw NullPointerException for null result")
        void shouldThrowForNullResult() {
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forUser("user-123")
            );
            
            assertThatThrownBy(() -> context.terminate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("termination result must not be null");
        }
    }
    
    @Nested
    @DisplayName("withMetadata() method")
    class WithMetadataMethod {
        
        @Test
        @DisplayName("Should add metadata to new context")
        void shouldAddMetadata() {
            // Arrange
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forUser("user-123")
            );
            
            // Act
            PipelineContext updated = context.withMetadata("key1", "value1");
            
            // Assert
            assertThat(updated.getMetadata()).containsEntry("key1", "value1");
            // Original unchanged
            assertThat(context.getMetadata()).isEmpty();
        }
        
        @Test
        @DisplayName("Should accumulate metadata across calls")
        void shouldAccumulateMetadata() {
            // Arrange
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forUser("user-123")
            );
            
            // Act
            PipelineContext updated = context
                .withMetadata("key1", "value1")
                .withMetadata("key2", "value2");
            
            // Assert
            assertThat(updated.getMetadata())
                .containsEntry("key1", "value1")
                .containsEntry("key2", "value2");
        }
    }
    
    @Nested
    @DisplayName("convenience methods")
    class ConvenienceMethods {
        
        @Test
        @DisplayName("getIdentifier() should return userId for authenticated user")
        void getIdentifierShouldReturnUserId() {
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forUser("user-123")
            );
            
            assertThat(context.getIdentifier()).isEqualTo("user-123");
        }
        
        @Test
        @DisplayName("getIdentifier() should return sessionId for anonymous user")
        void getIdentifierShouldReturnSessionId() {
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forSession("session-456")
            );
            
            assertThat(context.getIdentifier()).isEqualTo("session-456");
        }
        
        @Test
        @DisplayName("isAuthenticated() should return true for authenticated user")
        void isAuthenticatedShouldReturnTrue() {
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forUser("user-123")
            );
            
            assertThat(context.isAuthenticated()).isTrue();
        }
        
        @Test
        @DisplayName("isAuthenticated() should return false for anonymous user")
        void isAuthenticatedShouldReturnFalse() {
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forSession("session-456")
            );
            
            assertThat(context.isAuthenticated()).isFalse();
            assertThat(context.isAnonymous()).isTrue();
        }

        @Test
        @DisplayName("trusted application caller should not be treated as anonymous")
        void trustedApplicationCallerShouldNotBeAnonymous() {
            TrustedExecutionContext trustedContext = new TrustedExecutionContext(
                new ExecutionPrincipal(
                    "account-resolution-service",
                    ExecutionPrincipalType.SERVICE
                ),
                new ExecutionSubjectRef("account", "account-42"),
                ExecutionSource.APPLICATION,
                "tenant-1",
                "resolver-app",
                Set.of("action:get_account_profile"),
                "correlation-1",
                null
            );
            PipelineContext context = PipelineContext.from(
                new OrchestrationRequest(
                    "Review the trusted account",
                    OrchestrationContext.builder().build(),
                    trustedContext,
                    ConversationPersistencePolicy.NEVER
                )
            );

            assertThat(context.getIdentifier()).isEqualTo("account-42");
            assertThat(context.getConversationOwnerIdentifier()).isNull();
            assertThat(context.isAuthenticated()).isTrue();
            assertThat(context.isAnonymous()).isFalse();
        }

        @Test
        @DisplayName("trusted subject and server-bound conversation owner stay distinct")
        void trustedSubjectAndConversationOwnerStayDistinct() {
            TrustedExecutionContext trustedContext = new TrustedExecutionContext(
                new ExecutionPrincipal(
                    "support-agent-7",
                    ExecutionPrincipalType.END_USER
                ),
                new ExecutionSubjectRef("account", "account-42"),
                ExecutionSource.INTERACTIVE,
                "tenant-1",
                "resolver-app",
                Set.of("specialist:account-resolver@1"),
                "correlation-1",
                null
            );
            PipelineContext context = PipelineContext.from(
                new OrchestrationRequest(
                    "Review the trusted account",
                    OrchestrationContext.builder()
                        .userId("conversation-owner-7")
                        .conversationId("conversation-7")
                        .build(),
                    trustedContext,
                    ConversationPersistencePolicy.READ_ONLY
                )
            );

            assertThat(context.getIdentifier()).isEqualTo("account-42");
            assertThat(context.getConversationOwnerIdentifier())
                .isEqualTo("conversation-owner-7");
        }
        
        @Test
        @DisplayName("getEffectiveQuery() should return processedQuery when available")
        void getEffectiveQueryShouldReturnProcessed() {
            PipelineContext context = PipelineContext.from(
                "original query", 
                OrchestrationContext.forUser("user-123")
            ).toBuilder()
                .processedQuery("redacted query")
                .build();
            
            assertThat(context.getEffectiveQuery()).isEqualTo("redacted query");
        }
        
        @Test
        @DisplayName("getEffectiveQuery() should return originalQuery when processedQuery is blank")
        void getEffectiveQueryShouldFallbackToOriginal() {
            PipelineContext context = PipelineContext.from(
                "original query", 
                OrchestrationContext.forUser("user-123")
            ).toBuilder()
                .processedQuery("")
                .build();
            
            assertThat(context.getEffectiveQuery()).isEqualTo("original query");
        }
    }
    
    @Nested
    @DisplayName("immutability")
    class Immutability {
        
        @Test
        @DisplayName("getMetadataView() should return unmodifiable map")
        void metadataViewShouldBeUnmodifiable() {
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forUser("user-123")
            ).withMetadata("key", "value");
            
            Map<String, Object> view = context.getMetadataView();
            
            assertThatThrownBy(() -> view.put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
        
        @Test
        @DisplayName("getDetectedPiiTypesView() should return unmodifiable list")
        void piiTypesViewShouldBeUnmodifiable() {
            PipelineContext context = PipelineContext.from(
                "query", 
                OrchestrationContext.forUser("user-123")
            ).toBuilder()
                .detectedPiiTypes(List.of("SSN", "EMAIL"))
                .build();
            
            List<String> view = context.getDetectedPiiTypesView();
            
            assertThatThrownBy(() -> view.add("PHONE"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
