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

    @Test
    void preservesEmptyProcessedQueryAsIntentionalRedaction() {
        PIIDetectionService piiDetectionService = mock(PIIDetectionService.class);
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setDetectionDirection(PIIDetectionDirection.INPUT);

        String query = "sk-secret";
        when(piiDetectionService.detectAndProcess(query)).thenReturn(PIIDetectionResult.builder()
            .originalQuery(query)
            .processedQuery("")
            .piiDetected(true)
            .detections(List.of(PIIDetection.builder()
                .type("API_TOKEN")
                .startIndex(0)
                .endIndex(query.length())
                .maskedValue("")
                .build()))
            .build());

        PipelineContext context = PipelineContext.from(query, OrchestrationContext.forTest());

        PipelineContext result = new PIIDetectionStep(piiDetectionService, properties).process(context);

        verify(piiDetectionService).detectAndProcess(query);
        assertThat(result.getProcessedQuery()).isEmpty();
        assertThat(result.getDetectedPiiTypesView()).containsExactly("API_TOKEN");
    }

    @Test
    void redactsWhenDetectionsArePresentEvenIfFlagIsFalse() {
        PIIDetectionService piiDetectionService = mock(PIIDetectionService.class);
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setDetectionDirection(PIIDetectionDirection.INPUT);

        String query = "email user@example.com";
        when(piiDetectionService.detectAndProcess(query)).thenReturn(PIIDetectionResult.builder()
            .originalQuery(query)
            .processedQuery(query)
            .piiDetected(false)
            .detections(List.of(PIIDetection.builder()
                .type("EMAIL")
                .startIndex(6)
                .endIndex(22)
                .maskedValue("***@***.***")
                .build()))
            .build());

        PipelineContext context = PipelineContext.from(query, OrchestrationContext.forTest());

        PipelineContext result = new PIIDetectionStep(piiDetectionService, properties).process(context);

        assertThat(result.getProcessedQuery()).isEqualTo("email ***@***.***");
        assertThat(result.getDetectedPiiTypesView()).containsExactly("EMAIL");
    }

    @Test
    void fallbackRedactionSkipsMalformedDetections() {
        PIIDetectionService piiDetectionService = mock(PIIDetectionService.class);
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setDetectionDirection(PIIDetectionDirection.INPUT);

        String query = "safe token";
        when(piiDetectionService.detectAndProcess(query)).thenReturn(PIIDetectionResult.builder()
            .originalQuery(query)
            .processedQuery(query)
            .piiDetected(true)
            .detections(List.of(
                PIIDetection.builder()
                    .type("BAD_NEGATIVE")
                    .startIndex(-1)
                    .endIndex(4)
                    .maskedValue("xxx")
                    .build(),
                PIIDetection.builder()
                    .type("BAD_EMPTY")
                    .startIndex(2)
                    .endIndex(2)
                    .maskedValue("xxx")
                    .build(),
                PIIDetection.builder()
                    .type("TOKEN")
                    .startIndex(5)
                    .endIndex(50)
                    .maskedValue("***")
                    .build()
            ))
            .build());

        PipelineContext context = PipelineContext.from(query, OrchestrationContext.forTest());

        PipelineContext result = new PIIDetectionStep(piiDetectionService, properties).process(context);

        assertThat(result.getProcessedQuery()).isEqualTo("safe ***");
        assertThat(result.getDetectedPiiTypesView())
            .containsExactly("BAD_NEGATIVE", "BAD_EMPTY", "TOKEN");
    }

    @Test
    void nullDetectionDirectionDefaultsToInputOutput() {
        PIIDetectionService piiDetectionService = mock(PIIDetectionService.class);
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setDetectionDirection(null);

        String query = "hello";
        when(piiDetectionService.detectAndProcess(query)).thenReturn(PIIDetectionResult.builder()
            .originalQuery(query)
            .processedQuery(query)
            .piiDetected(false)
            .detections(List.of())
            .build());

        PipelineContext context = PipelineContext.from(query, OrchestrationContext.forTest());

        PipelineContext result = new PIIDetectionStep(piiDetectionService, properties).process(context);

        verify(piiDetectionService).detectAndProcess(query);
        assertThat(result.getProcessedQuery()).isEqualTo(query);
    }
}
