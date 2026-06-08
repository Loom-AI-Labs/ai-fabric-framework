package com.ai.infrastructure.intent.orchestration.rag;

import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTarget;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministically composes the embedding query string used for vector retrieval.
 *
 * <p>This is intentionally bounded and mode-gated to avoid "pinned target pollution" in broad searches.</p>
 */
public final class EmbeddingQueryComposer {

    private EmbeddingQueryComposer() {
    }

    public static final String METADATA_KEY_USER_QUERY = "userQuery";
    public static final String METADATA_KEY_EMBEDDING_QUERY = "embeddingQuery";
    public static final String METADATA_KEY_TARGET_HINT_ENABLED = "targetHintEnabled";
    public static final String METADATA_KEY_TARGET_HINT_APPLIED = "targetHintApplied";
    public static final String METADATA_KEY_TARGET_HINT_TARGETS_USED = "targetHintTargetsUsed";
    public static final String METADATA_KEY_TARGET_HINT_CHARS = "targetHintChars";

    public record Result(
        String embeddingQuery,
        boolean targetHintEnabled,
        boolean targetHintApplied,
        int targetHintTargetsUsed,
        int targetHintChars
    ) {
    }

    public static Result compose(String baseQuery,
                                 Intent intent,
                                 PipelineContext pipelineContext,
                                 OrchestrationProperties orchestrationProperties) {
        String normalizedBase = normalizeSingleLine(baseQuery);

        OrchestrationProperties.TargetHintProperties cfg = resolveTargetHintConfig(
            pipelineContext != null ? pipelineContext.getOrchestrationPolicy() : null,
            orchestrationProperties
        );

        boolean enabled = cfg != null && cfg.isEnabled();
        if (!enabled) {
            return new Result(normalizedBase, false, false, 0, 0);
        }

        boolean requiresTargetResolution = intent != null && Boolean.TRUE.equals(intent.getRequiresTargetResolution());
        if (!requiresTargetResolution) {
            return new Result(normalizedBase, true, false, 0, 0);
        }

        List<ResolvedTarget> targets = pipelineContext != null ? pipelineContext.getResolvedTargets() : null;
        if (targets == null || targets.isEmpty()) {
            return new Result(normalizedBase, true, false, 0, 0);
        }

        Hint hint = buildTargetsHint(targets, cfg);
        if (!hint.applied) {
            return new Result(normalizedBase, true, false, 0, 0);
        }

        String embeddingQuery = normalizedBase;
        if (StringUtils.hasText(hint.text)) {
            embeddingQuery = StringUtils.hasText(embeddingQuery)
                ? (embeddingQuery + " " + hint.text).trim()
                : hint.text.trim();
        }

        return new Result(embeddingQuery, true, true, hint.targetsUsed, hint.chars);
    }

    private static OrchestrationProperties.TargetHintProperties resolveTargetHintConfig(OrchestrationPolicy policy,
                                                                                       OrchestrationProperties properties) {
        OrchestrationProperties.TargetHintProperties base = properties != null
            && properties.getRag() != null
            && properties.getRag().getTargetHint() != null
            ? properties.getRag().getTargetHint()
            : null;

        if (base == null) {
            return null;
        }

        boolean enabled = base.isEnabled();
        String mode = policy != null ? policy.mode() : null;
        if (properties != null && StringUtils.hasText(mode) && properties.getModes() != null && !properties.getModes().isEmpty()) {
            OrchestrationProperties.ModeOverrides overrides = lookupCaseInsensitive(properties.getModes(), mode);
            if (overrides != null && overrides.getRagTargetHintEnabled() != null) {
                enabled = overrides.getRagTargetHintEnabled();
            }
        }

        OrchestrationProperties.TargetHintProperties resolved = new OrchestrationProperties.TargetHintProperties();
        resolved.setEnabled(enabled);
        resolved.setMaxTargets(base.getMaxTargets());
        resolved.setMaxChars(base.getMaxChars());
        resolved.setIncludeVectorSpace(base.isIncludeVectorSpace());
        resolved.setIncludeId(base.isIncludeId());
        resolved.setIncludeContentText(base.isIncludeContentText());
        resolved.setMaxContentTextCharsPerTarget(base.getMaxContentTextCharsPerTarget());
        resolved.setMetadataKeysAllowlist(base.getMetadataKeysAllowlist() != null
            ? new ArrayList<>(base.getMetadataKeysAllowlist())
            : List.of());
        return resolved;
    }

    private static OrchestrationProperties.ModeOverrides lookupCaseInsensitive(Map<String, OrchestrationProperties.ModeOverrides> map,
                                                                              String normalizedKey) {
        if (map == null || map.isEmpty() || !StringUtils.hasText(normalizedKey)) {
            return null;
        }
        String normalized = normalizedKey.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, OrchestrationProperties.ModeOverrides> entry : map.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (normalized.equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private record Hint(boolean applied, String text, int targetsUsed, int chars) {
    }

    private static Hint buildTargetsHint(List<ResolvedTarget> targets, OrchestrationProperties.TargetHintProperties cfg) {
        if (targets == null || targets.isEmpty() || cfg == null) {
            return new Hint(false, null, 0, 0);
        }

        int maxTargets = cfg.getMaxTargets() > 0 ? cfg.getMaxTargets() : 0;
        if (maxTargets == 0) {
            return new Hint(false, null, 0, 0);
        }

        int maxChars = cfg.getMaxChars() > 0 ? cfg.getMaxChars() : 0;
        if (maxChars == 0) {
            return new Hint(false, null, 0, 0);
        }

        List<String> allowlist = normalizeAllowlist(cfg.getMetadataKeysAllowlist());

        StringBuilder sb = new StringBuilder(Math.min(256, maxChars));
        sb.append("Targets:");

        int used = 0;
        for (ResolvedTarget target : targets) {
            if (target == null) {
                continue;
            }
            if (used >= maxTargets) {
                break;
            }

            String block = renderTargetBlock(target, cfg, allowlist);
            if (!StringUtils.hasText(block)) {
                continue;
            }

            int projected = sb.length() + 1 + block.length();
            if (projected > maxChars) {
                break;
            }

            sb.append(' ').append(block);
            used++;
        }

        String text = normalizeSingleLine(sb.toString());
        if (!StringUtils.hasText(text) || used == 0) {
            return new Hint(false, null, 0, 0);
        }

        return new Hint(true, text, used, text.length());
    }

    private static String renderTargetBlock(ResolvedTarget target,
                                           OrchestrationProperties.TargetHintProperties cfg,
                                           List<String> allowlist) {
        if (target == null || cfg == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(128);
        sb.append('[');

        boolean appended = false;
        if (cfg.isIncludeVectorSpace() && StringUtils.hasText(target.getVectorSpace())) {
            String value = normalizeAtom(target.getVectorSpace(), 64);
            if (StringUtils.hasText(value)) {
                sb.append("vectorSpace=").append(value);
                appended = true;
            }
        }

        if (cfg.isIncludeId() && StringUtils.hasText(target.getId())) {
            String value = normalizeAtom(target.getId(), 64);
            if (StringUtils.hasText(value)) {
                if (appended) {
                    sb.append(' ');
                }
                sb.append("id=").append(value);
                appended = true;
            }
        }

        if (allowlist != null && !allowlist.isEmpty()) {
            Map<String, String> normalizedMetadata = new LinkedHashMap<>();
            if (target.getMetadata() != null && !target.getMetadata().isEmpty()) {
                target.getMetadata().forEach((key, value) -> {
                    if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
                        return;
                    }
                    normalizedMetadata.put(key.trim().toLowerCase(Locale.ROOT), value);
                });
            }

            for (String key : allowlist) {
                if (!StringUtils.hasText(key)) {
                    continue;
                }
                if (shouldSkipReservedKeyBecauseAlreadyIncluded(key, target, cfg)) {
                    continue;
                }
                String raw = normalizedMetadata.get(key);
                if (!StringUtils.hasText(raw)) {
                    raw = resolveReservedRootValue(target, key, cfg);
                }
                String normalized = normalizeAtom(raw, 96);
                if (!StringUtils.hasText(normalized) || containsEmailMarker(normalized)) {
                    continue;
                }
                if (appended) {
                    sb.append(' ');
                }
                sb.append(key).append('=').append(normalized);
                appended = true;
            }
        }

        if (cfg.isIncludeContentText() && StringUtils.hasText(target.getContentText())) {
            String snippet = normalizeSingleLine(target.getContentText());
            int max = cfg.getMaxContentTextCharsPerTarget() > 0 ? cfg.getMaxContentTextCharsPerTarget() : 0;
            if (max > 0 && snippet.length() > max) {
                snippet = snippet.substring(0, max);
            }
            snippet = snippet.replace('"', '\'');
            if (StringUtils.hasText(snippet) && !containsEmailMarker(snippet)) {
                if (appended) {
                    sb.append(' ');
                }
                sb.append("text=\"").append(snippet).append('"');
                appended = true;
            }
        }

        sb.append(']');

        return appended ? sb.toString() : null;
    }

    private static boolean shouldSkipReservedKeyBecauseAlreadyIncluded(String normalizedKey,
                                                                      ResolvedTarget target,
                                                                      OrchestrationProperties.TargetHintProperties cfg) {
        if (!StringUtils.hasText(normalizedKey) || cfg == null || target == null) {
            return false;
        }
        return (cfg.isIncludeId() && StringUtils.hasText(target.getId()) && "id".equals(normalizedKey))
            || (cfg.isIncludeVectorSpace() && StringUtils.hasText(target.getVectorSpace()) && ("vectorspace".equals(normalizedKey) || "type".equals(normalizedKey)))
            || (cfg.isIncludeContentText() && StringUtils.hasText(target.getContentText()) && ("text".equals(normalizedKey) || "content".equals(normalizedKey) || "contenttext".equals(normalizedKey)));
    }

    /**
     * Some allowlisted attributes may be present on the target root object rather than in metadata.
     *
     * <p>This remains domain-agnostic by only supporting a small set of reserved keys.</p>
     */
    private static String resolveReservedRootValue(ResolvedTarget target,
                                                  String normalizedKey,
                                                  OrchestrationProperties.TargetHintProperties cfg) {
        if (target == null || cfg == null || !StringUtils.hasText(normalizedKey)) {
            return null;
        }

        return switch (normalizedKey) {
            case "id" -> normalizeAtom(target.getId(), 64);
            case "vectorspace", "type" -> normalizeAtom(target.getVectorSpace(), 64);
            case "text", "content", "contenttext" -> quoteIfPresent(target.getContentText(), cfg.getMaxContentTextCharsPerTarget());
            default -> null;
        };
    }

    private static String quoteIfPresent(String raw, int maxLen) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String snippet = normalizeSingleLine(raw);
        int max = maxLen > 0 ? maxLen : 0;
        if (max > 0 && snippet.length() > max) {
            snippet = snippet.substring(0, max);
        }
        snippet = snippet.replace('"', '\'');
        return StringUtils.hasText(snippet) ? ("\"" + snippet + "\"") : null;
    }

    private static List<String> normalizeAllowlist(List<String> rawKeys) {
        if (rawKeys == null || rawKeys.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(rawKeys.size());
        for (String key : rawKeys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && out.stream().noneMatch(existing -> Objects.equals(existing, normalized))) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static String normalizeSingleLine(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String collapsed = value.replace('\n', ' ').replace('\r', ' ').trim();
        StringBuilder out = new StringBuilder(collapsed.length());
        boolean lastWasWhitespace = false;
        for (int i = 0; i < collapsed.length(); i++) {
            char c = collapsed.charAt(i);
            boolean whitespace = Character.isWhitespace(c);
            if (whitespace) {
                if (!lastWasWhitespace) {
                    out.append(' ');
                }
                lastWasWhitespace = true;
                continue;
            }
            out.append(c);
            lastWasWhitespace = false;
        }
        return out.toString().trim();
    }

    private static String normalizeAtom(String value, int maxLen) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = normalizeSingleLine(value);
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (maxLen > 0 && normalized.length() > maxLen) {
            normalized = normalized.substring(0, maxLen);
        }
        return normalized;
    }

    private static boolean containsEmailMarker(String value) {
        return value != null && value.indexOf('@') >= 0;
    }
}
