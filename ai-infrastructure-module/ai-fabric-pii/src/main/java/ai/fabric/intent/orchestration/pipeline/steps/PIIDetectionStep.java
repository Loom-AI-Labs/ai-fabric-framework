package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.PIIDetectionProperties;
import ai.fabric.config.PIIDetectionProperties.PIIDetectionDirection;
import ai.fabric.dto.PIIDetection;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.privacy.pii.PIIDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

/**
 * Pipeline step that (optionally) detects and redacts Personally Identifiable Information (PII)
 * in the user query before downstream processing.
 *
 * <p><strong>Order:</strong> 30 (after access control)</p>
 */
@Slf4j
@RequiredArgsConstructor
public class PIIDetectionStep implements PipelineStep {

    private static final String STEP_NAME = "PIIDetection";
    private static final int STEP_ORDER = 30;

    private final PIIDetectionService piiDetectionService;
    private final PIIDetectionProperties piiDetectionProperties;

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    @Override
    public PipelineContext process(PipelineContext context) {
        if (piiDetectionService == null || piiDetectionProperties == null) {
            return context;
        }

        PIIDetectionDirection detectionDirection = piiDetectionProperties.getDetectionDirection();
        if (detectionDirection == null) {
            detectionDirection = PIIDetectionDirection.INPUT_OUTPUT;
        }
        boolean detectInput = piiDetectionProperties.isEnabled() &&
            (detectionDirection == PIIDetectionDirection.INPUT ||
                detectionDirection == PIIDetectionDirection.INPUT_OUTPUT);

        if (!detectInput) {
            return context;
        }

        String inputQuery = context.getEffectiveQuery();
        if (!StringUtils.hasText(inputQuery)) {
            return context;
        }

        PIIDetectionResult piiResult = piiDetectionService.detectAndProcess(inputQuery);
        if (piiResult == null) {
            return context;
        }

        List<PIIDetection> detections = piiResult.getDetections() != null ? piiResult.getDetections() : List.of();
        boolean hasPii = piiResult.isPiiDetected() || !detections.isEmpty();

        String processedQuery = piiResult.getProcessedQuery() != null
            ? piiResult.getProcessedQuery()
            : inputQuery;

        // Safety invariant: never forward raw PII to downstream LLM steps.
        // Even when PIIMode is DETECT_ONLY (which stores the original payload and emits detections),
        // we still pass a masked version into the orchestration pipeline so providers do not see
        // secrets/PII and to reduce provider refusals / OUT_OF_SCOPE misclassifications.
        if (hasPii
            && processedQuery.equals(inputQuery)
            && !detections.isEmpty()) {
            processedQuery = redact(inputQuery, detections);
        }

        List<String> detectedTypes = detections.stream()
            .map(PIIDetection::getType)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();

        if (hasPii) {
            log.info("PII detected in user query - types: {}", detectedTypes);
        }

        return context.toBuilder()
            .processedQuery(processedQuery)
            .detectedPiiTypes(detectedTypes)
            .build();
    }

    private String redact(String original, List<PIIDetection> detections) {
        if (!StringUtils.hasText(original) || detections == null || detections.isEmpty()) {
            return original;
        }

        StringBuilder builder = new StringBuilder(original);
        detections.stream()
            .filter(detection -> canApply(detection, builder.length()))
            .sorted(Comparator.comparingInt((PIIDetection d) -> d.getStartIndex()).reversed())
            .forEach(detection -> {
                int start = Math.min(detection.getStartIndex(), builder.length());
                int end = Math.max(start, Math.min(detection.getEndIndex(), builder.length()));
                builder.replace(start, end, detection.getMaskedValue());
            });
        return builder.toString();
    }

    private boolean canApply(PIIDetection detection, int inputLength) {
        return detection != null
            && detection.getMaskedValue() != null
            && detection.getStartIndex() >= 0
            && detection.getStartIndex() < inputLength
            && detection.getEndIndex() > detection.getStartIndex();
    }
}
