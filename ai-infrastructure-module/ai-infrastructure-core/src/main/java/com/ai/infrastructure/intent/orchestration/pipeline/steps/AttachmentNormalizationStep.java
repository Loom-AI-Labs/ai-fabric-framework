package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AttachmentsProperties;
import com.ai.infrastructure.dto.PIIDetectionResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.attachment.NormalizedAttachment;
import com.ai.infrastructure.intent.orchestration.attachment.OrchestrationAttachment;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.privacy.pii.PIIDetectionService;
import com.ai.infrastructure.intent.KnowledgeBaseOverview;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes request attachments into a bounded, scalar-only structure for downstream steps.
 *
 * <p><strong>Order:</strong> 23 (after policy resolution, before conversation enrichment)</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentNormalizationStep implements PipelineStep {

    private static final String STEP_NAME = "AttachmentNormalization";
    private static final int STEP_ORDER = 23;

    private static final String METADATA_KEY_ATTACHMENTS = "attachments";
    private static final String META_KEY_INVALID_VECTOR_SPACES = "invalidVectorSpaces";
    private static final String META_KEY_INVALID_VECTOR_SPACES_COUNT = "invalidVectorSpacesCount";
    private static final String META_KEY_VECTOR_SPACE_VALIDATION_MODE = "vectorSpaceValidationMode";

    private final AttachmentsProperties properties;
    private final ObjectProvider<PIIDetectionService> piiDetectionServiceProvider;
    private final ObjectProvider<KnowledgeBaseOverviewService> knowledgeBaseOverviewServiceProvider;

    private record NormalizedText(String value, boolean truncated) {}

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
        if (context == null || context.isShouldTerminate()) {
            return context;
        }
        if (properties != null && !properties.isEnabled()) {
            return context;
        }
        OrchestrationContext orchContext = context.getOrchestrationContext();
        if (orchContext == null) {
            return context;
        }

        List<OrchestrationAttachment> raw = orchContext.getAttachments();
        if (raw == null || raw.isEmpty()) {
            return context;
        }

        PIIDetectionService pii = piiDetectionServiceProvider != null ? piiDetectionServiceProvider.getIfAvailable() : null;

        int maxAttachments = Math.max(0, properties != null ? properties.getMaxAttachments() : 10);

        List<NormalizedAttachment> normalized = new ArrayList<>();
        boolean attachmentsTruncated = false;

        int provided = raw.size();
        if (raw != null && !raw.isEmpty() && maxAttachments > 0) {
            for (OrchestrationAttachment attachment : raw) {
                if (normalized.size() >= maxAttachments) {
                    attachmentsTruncated = true;
                    break;
                }
                NormalizedAttachment normalizedAttachment = normalizeAttachment(attachment, pii);
                if (normalizedAttachment != null) {
                    normalized.add(normalizedAttachment);
                }
            }
            if (raw.size() > maxAttachments) {
                attachmentsTruncated = true;
            }
        } else if (raw != null && !raw.isEmpty()) {
            attachmentsTruncated = true;
        }

        VectorSpaceValidationOutcome validation = validateAttachmentVectorSpaces(normalized);
        if (validation.shouldTerminate) {
            return context.terminate(validation.result);
        }
        if (validation.updatedAttachments != null) {
            normalized = validation.updatedAttachments;
        }

        OrchestrationContext updatedOrchContext = orchContext.toBuilder()
            .attachmentsNormalized(List.copyOf(normalized))
            .build();

        Map<String, Object> attachmentMeta = new LinkedHashMap<>();
        attachmentMeta.put("providedCount", provided);
        attachmentMeta.put("acceptedCount", normalized.size());
        attachmentMeta.put("truncated", attachmentsTruncated);
        if (properties != null && properties.getVectorSpaceValidationMode() != null) {
            attachmentMeta.put(META_KEY_VECTOR_SPACE_VALIDATION_MODE, properties.getVectorSpaceValidationMode().name());
        }
        if (validation.invalidVectorSpaces != null && !validation.invalidVectorSpaces.isEmpty()) {
            attachmentMeta.put(META_KEY_INVALID_VECTOR_SPACES_COUNT, validation.invalidVectorSpaces.size());
            attachmentMeta.put(META_KEY_INVALID_VECTOR_SPACES, List.copyOf(validation.invalidVectorSpaces));
        }

        PipelineContext updated = context.toBuilder()
            .orchestrationContext(updatedOrchContext)
            .build();

        return updated.withMetadata(METADATA_KEY_ATTACHMENTS, Collections.unmodifiableMap(attachmentMeta));
    }

    private NormalizedAttachment normalizeAttachment(OrchestrationAttachment attachment, PIIDetectionService pii) {
        if (attachment == null) {
            return null;
        }

        String id = normalizeToken(attachment.getId(), properties != null ? properties.getMaxIdChars() : 80, false, pii);
        String vectorSpace = normalizeToken(attachment.getVectorSpace(), properties != null ? properties.getMaxVectorSpaceChars() : 80, false, pii);

        NormalizedText content = normalizeContentText(
            attachment.getContentText(),
            properties != null ? properties.getMaxContentTextChars() : 2000,
            pii
        );

        Map<String, String> metadata = normalizeMetadata(attachment.getMetadata(), pii);

        String source = normalizeToken(
            attachment.getSource(),
            properties != null ? properties.getMaxSourceChars() : 60,
            false,
            pii
        );

        boolean hasId = StringUtils.hasText(id);
        boolean hasContentText = content != null && StringUtils.hasText(content.value());
        boolean hasMetadata = metadata != null && !metadata.isEmpty();
        if (!hasId && !hasContentText && !hasMetadata) {
            return null;
        }

        return NormalizedAttachment.builder()
            .id(id)
            .vectorSpace(vectorSpace)
            .contentText(content != null ? content.value() : null)
            .contentTextTruncated(content != null && content.truncated())
            .metadata(metadata)
            .source(source)
            .build();
    }

    private NormalizedText normalizeContentText(String input, int maxChars, PIIDetectionService pii) {
        if (!StringUtils.hasText(input)) {
            return new NormalizedText(null, false);
        }

        String normalized = input.trim();
        normalized = normalized.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        // Preserve some whitespace but collapse extremes.
        normalized = normalized.replaceAll("\\s{3,}", "  ");

        if (!StringUtils.hasText(normalized)) {
            return new NormalizedText(null, false);
        }

        boolean truncated = false;
        if (maxChars > 0 && normalized.length() > maxChars) {
            truncated = true;
            normalized = normalized.substring(0, maxChars).trim();
        }

        if (!StringUtils.hasText(normalized)) {
            return new NormalizedText(null, truncated);
        }

        if (pii != null) {
            try {
                PIIDetectionResult result = pii.detectAndProcess(normalized);
                if (result != null && StringUtils.hasText(result.getProcessedQuery())) {
                    normalized = result.getProcessedQuery().trim();
                }
            } catch (Exception ex) {
                // Never fail the request due to optional PII processing.
                log.debug("PII normalization failed: {}", ex.getMessage());
            }
        }

        return new NormalizedText(StringUtils.hasText(normalized) ? normalized : null, truncated);
    }

    private record VectorSpaceValidationOutcome(
        boolean shouldTerminate,
        OrchestrationResult result,
        List<NormalizedAttachment> updatedAttachments,
        List<String> invalidVectorSpaces
    ) {}

    private VectorSpaceValidationOutcome validateAttachmentVectorSpaces(List<NormalizedAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return new VectorSpaceValidationOutcome(false, null, null, List.of());
        }
        if (properties == null || properties.getVectorSpaceValidationMode() == null) {
            return new VectorSpaceValidationOutcome(false, null, null, List.of());
        }

        KnowledgeBaseOverviewService overviewService = knowledgeBaseOverviewServiceProvider != null
            ? knowledgeBaseOverviewServiceProvider.getIfAvailable()
            : null;
        if (overviewService == null) {
            return new VectorSpaceValidationOutcome(false, null, null, List.of());
        }

        KnowledgeBaseOverview overview;
        try {
            overview = overviewService.getOverview();
        } catch (Exception ex) {
            log.debug("Unable to validate attachment vectorSpace values: {}", ex.getMessage());
            return new VectorSpaceValidationOutcome(false, null, null, List.of());
        }

        List<String> allowed = overview != null ? overview.getEntityTypes() : null;
        if (allowed == null || allowed.isEmpty()) {
            return new VectorSpaceValidationOutcome(false, null, null, List.of());
        }

        Map<String, String> canonicalByLower = new LinkedHashMap<>();
        for (String space : allowed) {
            if (!StringUtils.hasText(space)) {
                continue;
            }
            canonicalByLower.put(space.trim().toLowerCase(java.util.Locale.ROOT), space.trim());
        }
        if (canonicalByLower.isEmpty()) {
            return new VectorSpaceValidationOutcome(false, null, null, List.of());
        }

        List<String> invalid = new ArrayList<>();
        List<NormalizedAttachment> updated = new ArrayList<>(attachments.size());

        for (NormalizedAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            String vectorSpace = attachment.getVectorSpace();
            if (!StringUtils.hasText(vectorSpace)) {
                updated.add(attachment);
                continue;
            }

            String normalized = vectorSpace.trim();
            String canonical = canonicalByLower.get(normalized.toLowerCase(java.util.Locale.ROOT));
            if (!StringUtils.hasText(canonical)) {
                invalid.add(normalized);
                // Best-effort: keep the attachment but do not propagate an invalid vectorSpace.
                updated.add(attachment.toBuilder().vectorSpace(null).build());
                continue;
            }

            if (!canonical.equals(vectorSpace)) {
                updated.add(attachment.toBuilder().vectorSpace(canonical).build());
            } else {
                updated.add(attachment);
            }
        }

        if (invalid.isEmpty()) {
            return new VectorSpaceValidationOutcome(false, null, updated, List.of());
        }

        if (properties.getVectorSpaceValidationMode() == AttachmentsProperties.VectorSpaceValidationMode.STRICT) {
            OrchestrationResult result = OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message("Invalid attachment vectorSpace. Use a known vectorSpace or omit it.")
                .data(Map.of(
                    "invalidVectorSpaces", List.copyOf(invalid),
                    "allowedVectorSpaces", List.copyOf(canonicalByLower.values())
                ))
                .build();
            return new VectorSpaceValidationOutcome(true, result, null, invalid);
        }

        return new VectorSpaceValidationOutcome(false, null, updated, invalid);
    }

    private Map<String, String> normalizeMetadata(Map<String, Object> raw, PIIDetectionService pii) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }

        int maxKeys = Math.max(0, properties != null ? properties.getMaxMetadataKeys() : 12);
        if (maxKeys == 0) {
            return Map.of();
        }

        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (out.size() >= maxKeys) {
                break;
            }
            if (entry == null) {
                continue;
            }

            String key = normalizeMetadataKey(entry.getKey());
            if (!StringUtils.hasText(key) || out.containsKey(key)) {
                continue;
            }

            String value = coerceScalarToString(entry.getValue());
            if (!StringUtils.hasText(value)) {
                continue;
            }

            value = normalizeToken(value, properties != null ? properties.getMaxMetadataValueChars() : 120, false, pii);
            if (!StringUtils.hasText(value)) {
                continue;
            }

            out.put(key, value);
        }

        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    private String normalizeMetadataKey(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        String trimmed = key.trim();
        trimmed = trimmed.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        trimmed = trimmed.replaceAll("\\s+", " ");
        if (!StringUtils.hasText(trimmed)) {
            return null;
        }
        // Keep keys short; values carry the content.
        if (trimmed.length() > 48) {
            trimmed = trimmed.substring(0, 48);
        }
        return trimmed;
    }

    private String coerceScalarToString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return null;
    }

    private String normalizeToken(String input, int maxChars, boolean allowLongerWhitespace, PIIDetectionService pii) {
        if (!StringUtils.hasText(input)) {
            return null;
        }

        String normalized = input.trim();
        normalized = normalized.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        if (!allowLongerWhitespace) {
            normalized = normalized.replaceAll("\\s+", " ");
        } else {
            // Preserve some whitespace but collapse extremes.
            normalized = normalized.replaceAll("\\s{3,}", "  ");
        }

        if (!StringUtils.hasText(normalized)) {
            return null;
        }

        if (maxChars > 0 && normalized.length() > maxChars) {
            normalized = normalized.substring(0, maxChars).trim();
        }

        if (!StringUtils.hasText(normalized)) {
            return null;
        }

        if (pii != null) {
            try {
                PIIDetectionResult result = pii.detectAndProcess(normalized);
                if (result != null && StringUtils.hasText(result.getProcessedQuery())) {
                    normalized = result.getProcessedQuery().trim();
                }
            } catch (Exception ex) {
                // Never fail the request due to optional PII processing.
                log.debug("PII normalization failed: {}", ex.getMessage());
            }
        }

        return StringUtils.hasText(normalized) ? normalized : null;
    }
}
