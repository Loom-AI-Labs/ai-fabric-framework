package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.PIIDetectionProperties;
import ai.fabric.config.PIIDetectionProperties.PIIDetectionDirection;
import ai.fabric.dto.PIIDetection;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.privacy.pii.PIIDetectionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PIIDetectionStepTest {

    @Test
    void usesEffectiveQueryWhenAlreadyProcessed() {
        PIIDetectionService piiDetectionService = mock(PIIDetectionService.class);
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setDetectionDirection(PIIDetectionDirection.INPUT);

        String originalQuery = "What is my account status?";
        String enrichedQuery = "Conversation History:\nUser: my SSN is 123-45-6789\nAssistant: ok\n\nCurrent Query:\n"
            + originalQuery;
        String redactedQuery = enrichedQuery.replace("123-45-6789", "***-**-****");

        when(piiDetectionService.detectAndProcess(enrichedQuery)).thenReturn(PIIDetectionResult.builder()
            .originalQuery(enrichedQuery)
            .processedQuery(redactedQuery)
            .piiDetected(true)
            .detections(List.of(PIIDetection.builder()
                .type("SSN")
                .startIndex(enrichedQuery.indexOf("123-45-6789"))
                .endIndex(enrichedQuery.indexOf("123-45-6789") + "123-45-6789".length())
                .maskedValue("***-**-****")
                .build()))
            .build());

        PipelineContext context = PipelineContext.from(originalQuery, OrchestrationContext.forTest())
            .toBuilder()
            .processedQuery(enrichedQuery)
            .build();

        PipelineContext result = new PIIDetectionStep(piiDetectionService, properties).process(context);

        verify(piiDetectionService).detectAndProcess(enrichedQuery);
        assertThat(result.getProcessedQuery()).isEqualTo(redactedQuery);
        assertThat(result.getDetectedPiiTypesView()).containsExactly("SSN");
    }

    @Test
    void redactsWhenDetectionsPresentButProcessedQueryUnchanged() {
        PIIDetectionService piiDetectionService = mock(PIIDetectionService.class);
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setDetectionDirection(PIIDetectionDirection.INPUT);

        String originalQuery = "original";
        String enrichedQuery = "hello 123456";

        when(piiDetectionService.detectAndProcess(enrichedQuery)).thenReturn(PIIDetectionResult.builder()
            .originalQuery(enrichedQuery)
            .processedQuery(enrichedQuery)
            .piiDetected(true)
            .detections(List.of(PIIDetection.builder()
                .type("NUMBER")
                .startIndex(6)
                .endIndex(12)
                .maskedValue("******")
                .build()))
            .build());

        PipelineContext context = PipelineContext.from(originalQuery, OrchestrationContext.forTest())
            .toBuilder()
            .processedQuery(enrichedQuery)
            .build();

        PipelineContext result = new PIIDetectionStep(piiDetectionService, properties).process(context);

        verify(piiDetectionService).detectAndProcess(enrichedQuery);
        assertThat(result.getProcessedQuery()).isEqualTo("hello ******");
        assertThat(result.getDetectedPiiTypesView()).containsExactly("NUMBER");
    }
}

