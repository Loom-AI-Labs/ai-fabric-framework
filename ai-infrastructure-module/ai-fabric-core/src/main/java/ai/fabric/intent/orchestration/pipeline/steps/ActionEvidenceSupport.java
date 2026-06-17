package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIChatRole;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Internal helpers for trusted action evidence normalization and schema traversal.
 */
final class ActionEvidenceSupport {

    private ActionEvidenceSupport() {
    }

    record EvidenceBundle(
        String userEvidenceLower,
        String pinnedEvidenceLower,
        Map<String, Set<String>> trustedValuesByKey,
        Map<String, Object> sourcesUsed
    ) {}

    static EvidenceBundle buildEvidenceBundle(PipelineContext pipelineContext) {
        if (pipelineContext == null) {
            return new EvidenceBundle(
                "",
                "",
                Map.of(),
                Map.of(
                    "user", false,
                    "history", false,
                    "pinned", false
                )
            );
        }

        StringBuilder userEvidence = new StringBuilder(512);
        boolean hasUser = false;
        if (StringUtils.hasText(pipelineContext.getOriginalQuery())) {
            userEvidence.append(pipelineContext.getOriginalQuery().trim());
            hasUser = true;
        }

        boolean hasHistory = false;
        if (pipelineContext.getHistoryMessages() != null && !pipelineContext.getHistoryMessages().isEmpty()) {
            for (AIChatMessage msg : pipelineContext.getHistoryMessages()) {
                if (msg == null || msg.getRole() != AIChatRole.USER) {
                    continue;
                }
                if (!StringUtils.hasText(msg.getContent())) {
                    continue;
                }
                userEvidence.append("\n").append(msg.getContent().trim());
                hasHistory = true;
            }
        }

        StringBuilder pinnedEvidence = new StringBuilder(512);
        boolean hasPinned = false;
        Map<String, Set<String>> trustedValuesByKey = new LinkedHashMap<>();

        OrchestrationContext orchContext = pipelineContext.getOrchestrationContext();
        List<NormalizedAttachment> attachments = orchContext != null ? orchContext.getAttachmentsNormalized() : null;
        if (attachments != null && !attachments.isEmpty()) {
            for (NormalizedAttachment attachment : attachments) {
                if (attachment == null) {
                    continue;
                }
                if (StringUtils.hasText(attachment.getId())) {
                    pinnedEvidence.append("\n").append(attachment.getId().trim());
                    hasPinned = true;
                }
                if (StringUtils.hasText(attachment.getVectorSpace())) {
                    pinnedEvidence.append("\n").append(attachment.getVectorSpace().trim());
                    hasPinned = true;
                }
                if (StringUtils.hasText(attachment.getContentText())) {
                    pinnedEvidence.append("\n").append(attachment.getContentText().trim());
                    hasPinned = true;
                }
                if (attachment.getMetadata() != null && !attachment.getMetadata().isEmpty()) {
                    addTrustedEvidenceValues(trustedValuesByKey, attachment.getMetadata());
                    for (String value : attachment.getMetadata().values()) {
                        if (!StringUtils.hasText(value)) {
                            continue;
                        }
                        pinnedEvidence.append("\n").append(value.trim());
                        hasPinned = true;
                    }
                }
            }
        }

        List<ResolvedTarget> targets = pipelineContext.getResolvedTargets();
        if (targets != null && !targets.isEmpty()) {
            for (ResolvedTarget target : targets) {
                if (target == null) {
                    continue;
                }
                if (StringUtils.hasText(target.getId())) {
                    pinnedEvidence.append("\n").append(target.getId().trim());
                    hasPinned = true;
                }
                if (StringUtils.hasText(target.getVectorSpace())) {
                    pinnedEvidence.append("\n").append(target.getVectorSpace().trim());
                    hasPinned = true;
                }
                if (StringUtils.hasText(target.getContentText())) {
                    pinnedEvidence.append("\n").append(target.getContentText().trim());
                    hasPinned = true;
                }
                if (target.getMetadata() != null && !target.getMetadata().isEmpty()) {
                    addTrustedEvidenceValues(trustedValuesByKey, target.getMetadata());
                    for (String value : target.getMetadata().values()) {
                        if (!StringUtils.hasText(value)) {
                            continue;
                        }
                        pinnedEvidence.append("\n").append(value.trim());
                        hasPinned = true;
                    }
                }
            }
        }

        boolean hasPendingConfirmationEvidence = addTrustedEvidenceValues(
            trustedValuesByKey,
            pipelineContext.getMetadata() != null
                ? pipelineContext.getMetadata().get(PendingAction.TRUSTED_EVIDENCE_METADATA_KEY)
                : null
        );

        Map<String, Object> sourcesUsed = Map.of(
            "user", hasUser,
            "history", hasHistory,
            "pinned", hasPinned,
            "pendingConfirmationEvidence", hasPendingConfirmationEvidence
        );

        return new EvidenceBundle(
            userEvidence.toString().toLowerCase(java.util.Locale.ROOT),
            pinnedEvidence.toString().toLowerCase(java.util.Locale.ROOT),
            freezeTrustedEvidenceValues(trustedValuesByKey),
            sourcesUsed
        );
    }

    static void addTrustedEvidenceValues(Map<String, Set<String>> trustedValuesByKey, Map<String, String> metadata) {
        if (trustedValuesByKey == null || metadata == null || metadata.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey()) || !StringUtils.hasText(entry.getValue())) {
                continue;
            }
            String key = normalizeEvidenceKey(entry.getKey());
            String value = normalizeEvidenceValue(entry.getValue());
            if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                continue;
            }
            trustedValuesByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(value);
        }
    }

    static boolean addTrustedEvidenceValues(Map<String, Set<String>> trustedValuesByKey, Object rawEvidence) {
        if (trustedValuesByKey == null || !(rawEvidence instanceof Map<?, ?> map) || map.isEmpty()) {
            return false;
        }
        boolean added = false;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry == null || entry.getKey() == null || !StringUtils.hasText(entry.getKey().toString())) {
                continue;
            }
            String key = normalizeEvidenceKey(entry.getKey().toString());
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object rawValue = entry.getValue();
            if (rawValue instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    added |= addTrustedEvidenceValue(trustedValuesByKey, key, value);
                }
            } else {
                added |= addTrustedEvidenceValue(trustedValuesByKey, key, rawValue);
            }
        }
        return added;
    }

    static boolean addTrustedEvidenceValue(Map<String, Set<String>> trustedValuesByKey, String normalizedKey, Object rawValue) {
        String value = normalizeEvidenceValue(rawValue);
        if (!StringUtils.hasText(normalizedKey) || !StringUtils.hasText(value)) {
            return false;
        }
        trustedValuesByKey.computeIfAbsent(normalizedKey, ignored -> new LinkedHashSet<>()).add(value);
        return true;
    }

    static Map<String, Set<String>> freezeTrustedEvidenceValues(Map<String, Set<String>> trustedValuesByKey) {
        if (trustedValuesByKey == null || trustedValuesByKey.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        trustedValuesByKey.forEach((key, values) -> {
            if (StringUtils.hasText(key) && values != null && !values.isEmpty()) {
                out.put(key, Set.copyOf(values));
            }
        });
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    static Map<String, List<String>> pendingTrustedEvidenceValues(EvidenceBundle evidence,
                                                                  AIActionMetaData meta,
                                                                  Map<String, Object> effectiveParams,
                                                                  Set<String> trustedResolvedParameters) {
        Set<String> evidenceKeys = evidenceBoundKeys(meta);
        if (evidenceKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> trustedValuesByKey = new LinkedHashMap<>();
        if (evidence != null && evidence.trustedValuesByKey() != null && !evidence.trustedValuesByKey().isEmpty()) {
            evidence.trustedValuesByKey().forEach((key, values) -> {
                String normalizedKey = normalizeEvidenceKey(key);
                if (StringUtils.hasText(normalizedKey)
                    && evidenceKeys.contains(normalizedKey)
                    && values != null
                    && !values.isEmpty()) {
                    for (String value : values) {
                        addTrustedEvidenceValue(trustedValuesByKey, normalizedKey, value);
                    }
                }
            });
        }

        addTrustedResolvedParamEvidenceValues(trustedValuesByKey, meta, effectiveParams, trustedResolvedParameters);

        Map<String, List<String>> out = new LinkedHashMap<>();
        trustedValuesByKey.forEach((key, values) -> {
            String normalizedKey = normalizeEvidenceKey(key);
            if (StringUtils.hasText(normalizedKey) && evidenceKeys.contains(normalizedKey) && values != null && !values.isEmpty()) {
                List<String> normalizedValues = values.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
                if (!normalizedValues.isEmpty()) {
                    out.put(normalizedKey, List.copyOf(normalizedValues));
                }
            }
        });
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    static Map<String, Object> mergeTrustedActionEvidence(Map<String, Object> metadata,
                                                          Map<String, List<String>> trustedEvidenceValuesByKey) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata != null ? metadata : Map.of());
        if (trustedEvidenceValuesByKey == null || trustedEvidenceValuesByKey.isEmpty()) {
            merged.remove(PendingAction.TRUSTED_EVIDENCE_METADATA_KEY);
            return Collections.unmodifiableMap(merged);
        }
        merged.put(
            PendingAction.TRUSTED_EVIDENCE_METADATA_KEY,
            Collections.unmodifiableMap(new LinkedHashMap<>(trustedEvidenceValuesByKey))
        );
        return Collections.unmodifiableMap(merged);
    }

    private static void addTrustedResolvedParamEvidenceValues(Map<String, Set<String>> trustedValuesByKey,
                                                              AIActionMetaData meta,
                                                              Map<String, Object> effectiveParams,
                                                              Set<String> trustedResolvedParameters) {
        if (trustedValuesByKey == null
            || meta == null
            || meta.getParameterSchemas() == null
            || meta.getParameterSchemas().isEmpty()
            || effectiveParams == null
            || effectiveParams.isEmpty()) {
            return;
        }
        Set<String> trustedResolved = ActionParameterSupport.normalizeParameterNameSet(trustedResolvedParameters);
        if (trustedResolved.isEmpty()) {
            return;
        }
        for (Map.Entry<String, AIActionParamSchema> entry : meta.getParameterSchemas().entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            String parameter = entry.getKey().trim();
            if (!trustedResolved.contains(parameter.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            Object value = ActionContextLookupSupport.valueByCandidateKeys(effectiveParams, List.of(parameter));
            collectTrustedResolvedEvidenceValues(trustedValuesByKey, value, entry.getValue());
        }
    }

    private static void collectTrustedResolvedEvidenceValues(Map<String, Set<String>> trustedValuesByKey,
                                                             Object value,
                                                             AIActionParamSchema schema) {
        if (trustedValuesByKey == null || schema == null || !ActionValueSupport.hasMeaningfulJavaValue(value)) {
            return;
        }
        if (Boolean.TRUE.equals(schema.getEvidenceBound())
            && !(value instanceof Map<?, ?>)
            && !(value instanceof Iterable<?>)) {
            List<String> configuredKeys = schema.getEvidenceKeys() != null && !schema.getEvidenceKeys().isEmpty()
                ? schema.getEvidenceKeys()
                : List.of(schema.getName());
            for (String key : configuredKeys) {
                String normalizedKey = normalizeEvidenceKey(key);
                if (StringUtils.hasText(normalizedKey)) {
                    addTrustedEvidenceValue(trustedValuesByKey, normalizedKey, value);
                }
            }
        }
        if (value instanceof Iterable<?> iterable && schema.getItems() != null) {
            for (Object item : iterable) {
                collectTrustedResolvedEvidenceValues(trustedValuesByKey, item, schema.getItems());
            }
            return;
        }
        if (value instanceof Map<?, ?> map && schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            for (Map.Entry<String, AIActionParamSchema> property : schema.getProperties().entrySet()) {
                if (property == null || !StringUtils.hasText(property.getKey()) || property.getValue() == null) {
                    continue;
                }
                Object propertyValue = ActionContextLookupSupport.valueByCandidateKeys(map, List.of(property.getKey().trim()));
                collectTrustedResolvedEvidenceValues(trustedValuesByKey, propertyValue, property.getValue());
            }
        }
    }

    static Set<String> evidenceBoundKeys(AIActionMetaData meta) {
        if (meta == null || meta.getParameterSchemas() == null || meta.getParameterSchemas().isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        meta.getParameterSchemas().values().forEach(schema -> collectEvidenceBoundKeys(schema, keys));
        return keys.isEmpty() ? Set.of() : Collections.unmodifiableSet(keys);
    }

    private static void collectEvidenceBoundKeys(AIActionParamSchema schema, Set<String> keys) {
        if (schema == null || keys == null) {
            return;
        }
        if (Boolean.TRUE.equals(schema.getEvidenceBound())) {
            List<String> configuredKeys = schema.getEvidenceKeys() != null && !schema.getEvidenceKeys().isEmpty()
                ? schema.getEvidenceKeys()
                : List.of(schema.getName());
            for (String key : configuredKeys) {
                String normalizedKey = normalizeEvidenceKey(key);
                if (StringUtils.hasText(normalizedKey)) {
                    keys.add(normalizedKey);
                }
            }
        }
        if (schema.getItems() != null) {
            collectEvidenceBoundKeys(schema.getItems(), keys);
        }
        if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
            schema.getProperties().values().forEach(child -> collectEvidenceBoundKeys(child, keys));
        }
    }

    static boolean isEvidenceBoundValueTrusted(Object value,
                                               AIActionParamSchema schema,
                                               Map<String, Set<String>> trustedValuesByKey) {
        if (value == null || schema == null || trustedValuesByKey == null) {
            return false;
        }
        String normalizedValue = normalizeEvidenceValue(value);
        if (!StringUtils.hasText(normalizedValue)) {
            return false;
        }
        List<String> keys = schema.getEvidenceKeys() != null && !schema.getEvidenceKeys().isEmpty()
            ? schema.getEvidenceKeys()
            : List.of(schema.getName());
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Set<String> trustedValues = trustedValuesByKey.get(normalizeEvidenceKey(key));
            if (trustedValues != null && trustedValues.contains(normalizedValue)) {
                return true;
            }
        }
        return false;
    }

    static String normalizeEvidenceKey(String key) {
        return StringUtils.hasText(key) ? key.trim().toLowerCase(java.util.Locale.ROOT) : "";
    }

    static String normalizeEvidenceValue(Object value) {
        return value != null && StringUtils.hasText(value.toString())
            ? value.toString().trim().toLowerCase(java.util.Locale.ROOT)
            : "";
    }
}
