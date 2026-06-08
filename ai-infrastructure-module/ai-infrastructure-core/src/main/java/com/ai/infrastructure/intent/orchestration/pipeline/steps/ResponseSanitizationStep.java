package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.security.ResponseSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pipeline step that sanitizes the response before returning to the client.
 * 
 * <p>This step uses the {@link ResponseSanitizer} to clean and secure the
 * response data. It also merges PII detection information from the input
 * processing stage.</p>
 * 
 * <p><strong>Order:</strong> 90 (after smart suggestions)</p>
 * 
 * @see ResponseSanitizer
 * @see PipelineStep
 * @since 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseSanitizationStep implements PipelineStep {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String STEP_NAME = "ResponseSanitization";
    private static final int STEP_ORDER = 90;
    
    // Sanitization keys
    private static final String SANITIZATION_KEY = "sanitization";
    private static final String DETECTED_TYPES_KEY = "detectedTypes";
    private static final String MESSAGE_KEY = "message";
    private static final String DATA_KEY = "data";
    private static final String ANSWER_KEY = "answer";
    
    // =========================================================================
    // Dependencies
    // =========================================================================
    
    private final ResponseSanitizer responseSanitizer;
    
    // =========================================================================
    // PipelineStep Implementation
    // =========================================================================
    
    /**
     * {@inheritDoc}
     */
    @Override
    public String getStepName() {
        return STEP_NAME;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    @Override
    public boolean shouldSkip(PipelineContext context) {
        return false;
    }
    
    /**
     * Sanitize the response and merge PII detection information.
     * 
     * <p>This step:</p>
     * <ol>
     *   <li>Calls the response sanitizer to clean the result</li>
     *   <li>Merges PII types detected in input with any output detections</li>
     *   <li>Sets the sanitized payload on the result</li>
     * </ol>
     * 
     * @param context the current pipeline context
     * @return updated context with sanitized payload
     */
    @Override
    public PipelineContext process(PipelineContext context) {
        log.debug("Sanitizing response for request {}", context.getRequestId());
        
        OrchestrationResult result = context.getIntentResult();
        if (result == null) {
            log.warn("No intent result available for sanitization in request {}", 
                context.getRequestId());
            return context;
        }
        
        String identifier = context.getIdentifier();

        applySanitizationToChildren(result, identifier, Collections.newSetFromMap(new IdentityHashMap<>()));
        
        // ResponseSanitizer guarantees a non-empty payload
        Map<String, Object> sanitizedPayload = responseSanitizer.sanitize(result, identifier);
        
        // Merge PII detection information
        List<String> inputPiiTypes = context.getDetectedPiiTypes();
        if (!inputPiiTypes.isEmpty() && sanitizedPayload.containsKey(SANITIZATION_KEY)) {
            sanitizedPayload = mergePiiDetectionInfo(sanitizedPayload, inputPiiTypes);
        }
        
        result.setSanitizedPayload(sanitizedPayload);
        applySanitizedTextMirrors(result, sanitizedPayload);
        
        log.debug("Response sanitization complete for request {}", context.getRequestId());
        
        return context.toBuilder()
            .sanitizedPayload(sanitizedPayload)
            .build();
    }

    private void applySanitizationToChildren(OrchestrationResult result,
                                             String identifier,
                                             Set<OrchestrationResult> visited) {
        if (result == null || !visited.add(result) || result.getChildren() == null || result.getChildren().isEmpty()) {
            return;
        }
        for (OrchestrationResult child : result.getChildren()) {
            if (child == null) {
                continue;
            }
            applySanitizationToChildren(child, identifier, visited);
            Map<String, Object> childPayload = responseSanitizer.sanitize(child, identifier);
            child.setSanitizedPayload(childPayload);
            applySanitizedTextMirrors(child, childPayload);
        }
    }
    
    // =========================================================================
    // Private Helper Methods
    // =========================================================================
    
    /**
     * Merge PII detection information from input into the sanitization metadata.
     * 
     * @param sanitizedPayload the current sanitized payload
     * @param inputPiiTypes PII types detected in the input
     * @return updated payload with merged PII information
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergePiiDetectionInfo(Map<String, Object> sanitizedPayload,
                                                       List<String> inputPiiTypes) {
        Map<String, Object> sanitization = (Map<String, Object>) sanitizedPayload.get(SANITIZATION_KEY);
        Map<String, Object> updatedSanitization = new LinkedHashMap<>(sanitization);
        
        List<String> existingTypes = (List<String>) sanitization.get(DETECTED_TYPES_KEY);
        List<String> mergedTypes = new ArrayList<>();
        if (existingTypes != null) {
            mergedTypes.addAll(existingTypes);
        }
        mergedTypes.addAll(inputPiiTypes);
        
        // Deduplicate and sort
        List<String> finalTypes = mergedTypes.stream()
            .distinct()
            .sorted()
            .collect(Collectors.toList());
        
        if (!finalTypes.isEmpty()) {
            updatedSanitization.put(DETECTED_TYPES_KEY, finalTypes);
        }
        
        // Create new payload map with updated sanitization if changed
        if (!updatedSanitization.equals(sanitization)) {
            Map<String, Object> updatedPayload = new LinkedHashMap<>(sanitizedPayload);
            updatedPayload.put(SANITIZATION_KEY, Collections.unmodifiableMap(updatedSanitization));
            return Collections.unmodifiableMap(updatedPayload);
        }
        
        return sanitizedPayload;
    }

    @SuppressWarnings("unchecked")
    private void applySanitizedTextMirrors(OrchestrationResult result, Map<String, Object> sanitizedPayload) {
        Object sanitizedMessage = sanitizedPayload.get(MESSAGE_KEY);
        if (sanitizedMessage instanceof String message) {
            result.setMessage(message);
        }

        Object sanitizedData = sanitizedPayload.get(DATA_KEY);
        if (!(sanitizedData instanceof Map<?, ?> sanitizedDataMap)) {
            return;
        }
        Object sanitizedAnswer = sanitizedDataMap.get(ANSWER_KEY);
        if (!(sanitizedAnswer instanceof String answer)) {
            return;
        }
        Map<String, Object> originalData = result.getData() != null
            ? result.getData()
            : Collections.emptyMap();
        if (answer.equals(originalData.get(ANSWER_KEY))) {
            return;
        }
        Map<String, Object> updatedData = new LinkedHashMap<>(originalData);
        updatedData.put(ANSWER_KEY, answer);
        result.setData(Collections.unmodifiableMap(updatedData));
    }
}
