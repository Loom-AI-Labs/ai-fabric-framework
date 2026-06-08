package com.ai.infrastructure.intent.orchestration.pipeline;

import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DefaultOrchestrationPipeline}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultOrchestrationPipeline")
class DefaultOrchestrationPipelineTest {
    
    @Nested
    @DisplayName("execute()")
    class ExecuteMethod {
        
        @Test
        @DisplayName("Should execute all steps in order")
        void shouldExecuteAllStepsInOrder() {
            // Arrange
            TestStep step1 = new TestStep("Step1", 10);
            TestStep step2 = new TestStep("Step2", 20);
            TestStep step3 = new TestStep("Step3", 30);
            
            // Add a final step that sets the intent result
            PipelineStep finalStep = new PipelineStep() {
                @Override
                public String getStepName() { return "Final"; }
                @Override
                public int getOrder() { return 100; }
                @Override
                public PipelineContext process(PipelineContext context) {
                    return context.toBuilder()
                        .intentResult(OrchestrationResult.builder()
                            .type(OrchestrationResultType.INFORMATION_PROVIDED)
                            .success(true)
                            .message("Success")
                            .build())
                        .build();
                }
            };
            
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(
                Arrays.asList(step2, step1, step3, finalStep) // Deliberately out of order
            );
            
            // Act
            OrchestrationResult result = pipeline.execute(
                "test query", 
                OrchestrationContext.forUser("user-123")
            );
            
            // Assert
            assertThat(result.isSuccess()).isTrue();
            assertThat(step1.wasExecuted()).isTrue();
            assertThat(step2.wasExecuted()).isTrue();
            assertThat(step3.wasExecuted()).isTrue();
            assertThat(result.getMetadata()).containsKey("timing");
            @SuppressWarnings("unchecked")
            var timing = (java.util.Map<String, Object>) result.getMetadata().get("timing");
            assertThat(timing).containsKeys("pipelineTotalDurationMs", "pipelineTerminatedEarly", "stepDurationsMs");
            assertThat(timing.get("pipelineTotalDurationMs")).isInstanceOf(Number.class);
            assertThat(timing.get("pipelineTerminatedEarly")).isEqualTo(false);
            @SuppressWarnings("unchecked")
            var stepDurations = (java.util.Map<String, Object>) timing.get("stepDurationsMs");
            assertThat(stepDurations).containsKeys("Step1", "Step2", "Step3", "Final");
            
            // Verify order (step1 before step2 before step3)
            assertThat(step1.getExecutionOrder()).isLessThan(step2.getExecutionOrder());
            assertThat(step2.getExecutionOrder()).isLessThan(step3.getExecutionOrder());
        }
        
        @Test
        @DisplayName("Should terminate early when step returns termination")
        void shouldTerminateEarlyOnTermination() {
            // Arrange
            TestStep step1 = new TestStep("Step1", 10);
            
            PipelineStep terminatingStep = new PipelineStep() {
                @Override
                public String getStepName() { return "Terminator"; }
                @Override
                public int getOrder() { return 20; }
                @Override
                public PipelineContext process(PipelineContext context) {
                    return context.terminate(OrchestrationResult.error("Terminated"));
                }
            };
            
            TestStep step3 = new TestStep("Step3", 30);
            
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(
                Arrays.asList(step1, terminatingStep, step3)
            );
            
            // Act
            OrchestrationResult result = pipeline.execute(
                "test query", 
                OrchestrationContext.forUser("user-123")
            );
            
            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).isEqualTo("Terminated");
            assertThat(step1.wasExecuted()).isTrue();
            assertThat(step3.wasExecuted()).isFalse(); // Should be skipped
            assertThat(result.getMetadata()).containsKey("timing");
            @SuppressWarnings("unchecked")
            var timing = (java.util.Map<String, Object>) result.getMetadata().get("timing");
            assertThat(timing.get("pipelineTerminatedEarly")).isEqualTo(true);
        }
        
        @Test
        @DisplayName("Should handle step exceptions gracefully")
        void shouldHandleStepExceptions() {
            // Arrange
            TestStep step1 = new TestStep("Step1", 10);
            
            PipelineStep throwingStep = new PipelineStep() {
                @Override
                public String getStepName() { return "Thrower"; }
                @Override
                public int getOrder() { return 20; }
                @Override
                public PipelineContext process(PipelineContext context) {
                    throw new RuntimeException("Step failed!");
                }
            };
            
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(
                Arrays.asList(step1, throwingStep)
            );
            
            // Act
            OrchestrationResult result = pipeline.execute(
                "test query", 
                OrchestrationContext.forUser("user-123")
            );
            
            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).contains("Pipeline step failed: Thrower");
        }
        
        @Test
        @DisplayName("Should throw for null query")
        void shouldThrowForNullQuery() {
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(List.of());
            
            assertThatThrownBy(() -> pipeline.execute(null, OrchestrationContext.forUser("user")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query must not be null");
        }
        
        @Test
        @DisplayName("Should throw for blank query")
        void shouldThrowForBlankQuery() {
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(List.of());
            
            assertThatThrownBy(() -> pipeline.execute("  ", OrchestrationContext.forUser("user")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query must not be blank");
        }
        
        @Test
        @DisplayName("Should throw for null context")
        void shouldThrowForNullContext() {
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(List.of());
            
            assertThatThrownBy(() -> pipeline.execute("query", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context must not be null");
        }
    }
    
    @Nested
    @DisplayName("getSteps()")
    class GetStepsMethod {
        
        @Test
        @DisplayName("Should return unmodifiable list of steps")
        void shouldReturnUnmodifiableList() {
            TestStep step1 = new TestStep("Step1", 10);
            TestStep step2 = new TestStep("Step2", 20);
            
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(
                Arrays.asList(step1, step2)
            );
            
            List<PipelineStep> steps = pipeline.getSteps();
            
            assertThatThrownBy(() -> steps.add(new TestStep("New", 30)))
                .isInstanceOf(UnsupportedOperationException.class);
        }
        
        @Test
        @DisplayName("Should return steps sorted by order")
        void shouldReturnStepsSortedByOrder() {
            TestStep step1 = new TestStep("Step1", 30);
            TestStep step2 = new TestStep("Step2", 10);
            TestStep step3 = new TestStep("Step3", 20);
            
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(
                Arrays.asList(step1, step2, step3)
            );
            
            List<PipelineStep> steps = pipeline.getSteps();
            
            assertThat(steps).extracting(PipelineStep::getStepName)
                .containsExactly("Step2", "Step3", "Step1");
        }
    }
    
    @Nested
    @DisplayName("getStepCount()")
    class GetStepCountMethod {
        
        @Test
        @DisplayName("Should return correct count")
        void shouldReturnCorrectCount() {
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(
                Arrays.asList(
                    new TestStep("Step1", 10),
                    new TestStep("Step2", 20),
                    new TestStep("Step3", 30)
                )
            );
            
            assertThat(pipeline.getStepCount()).isEqualTo(3);
        }
        
        @Test
        @DisplayName("Should return zero for empty pipeline")
        void shouldReturnZeroForEmptyPipeline() {
            DefaultOrchestrationPipeline pipeline = new DefaultOrchestrationPipeline(List.of());
            
            assertThat(pipeline.getStepCount()).isZero();
        }
    }
    
    // =========================================================================
    // Test Helper
    // =========================================================================
    
    /**
     * Simple test step that tracks execution.
     */
    private static class TestStep implements PipelineStep {
        private final String name;
        private final int order;
        private boolean executed = false;
        private int executionOrder = -1;
        private static int executionCounter = 0;
        
        TestStep(String name, int order) {
            this.name = name;
            this.order = order;
        }
        
        @Override
        public String getStepName() {
            return name;
        }
        
        @Override
        public int getOrder() {
            return order;
        }
        
        @Override
        public PipelineContext process(PipelineContext context) {
            executed = true;
            executionOrder = executionCounter++;
            return context;
        }
        
        boolean wasExecuted() {
            return executed;
        }
        
        int getExecutionOrder() {
            return executionOrder;
        }
    }
}
