package com.ai.infrastructure.intent.orchestration.targets;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders a list of {@link ResolvedTarget}s into a compact, LLM-visible block.
 *
 * <p>This is intended for prompt context only; it must never be used as an embeddings query input.</p>
 */
public final class ResolvedTargetsContextRenderer {

    private ResolvedTargetsContextRenderer() {
    }

    public static String render(String header, String refPrefix, List<ResolvedTarget> targets) {
        if (!StringUtils.hasText(header) || targets == null || targets.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append(header.trim()).append("\n");

        appendTargets(sb, refPrefix, targets, 1);

        return sb.toString().trim();
    }

    /**
     * Render targets grouped by {@link ResolvedTargetSource}, preserving a single global ref index.
     *
     * <p>Use this when the caller needs the LLM to see multiple pinned-target origins (e.g. "write result"
     * vs "user selection") without storing two separate pinned-target lists.</p>
     */
    public static String renderGrouped(String header,
                                       String refPrefix,
                                       List<ResolvedTarget> targets,
                                       List<ResolvedTargetSource> groupOrder,
                                       Map<ResolvedTargetSource, String> groupLabels,
                                       String otherGroupLabel) {
        if (!StringUtils.hasText(header) || targets == null || targets.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder(768);
        sb.append(header.trim()).append("\n");

        Set<ResolvedTargetSource> ordered;
        if (groupOrder == null || groupOrder.isEmpty()) {
            ordered = Set.of();
        } else {
            java.util.LinkedHashSet<ResolvedTargetSource> safe = new java.util.LinkedHashSet<>();
            for (ResolvedTargetSource source : groupOrder) {
                if (source != null) {
                    safe.add(source);
                }
            }
            ordered = safe.isEmpty() ? Set.of() : Set.copyOf(safe);
        }
        int index = 1;

        if (groupOrder != null && !groupOrder.isEmpty()) {
            for (ResolvedTargetSource source : groupOrder) {
                if (source == null) {
                    continue;
                }
                List<ResolvedTarget> group = targets.stream()
                    .filter(t -> t != null && t.getSource() == source)
                    .toList();
                if (group.isEmpty()) {
                    continue;
                }

                String label = groupLabels != null ? groupLabels.get(source) : null;
                if (!StringUtils.hasText(label)) {
                    label = source.name();
                }
                sb.append(label.trim()).append("\n");
                index = appendTargets(sb, refPrefix, group, index);
                sb.append("\n");
            }
        }

        List<ResolvedTarget> other = targets.stream()
            .filter(t -> t != null && (t.getSource() == null || !ordered.contains(t.getSource())))
            .toList();
        if (!other.isEmpty()) {
            String label = StringUtils.hasText(otherGroupLabel) ? otherGroupLabel.trim() : "Other:";
            sb.append(label).append("\n");
            appendTargets(sb, refPrefix, other, index);
        }

        return sb.toString().trim();
    }

    private static int appendTargets(StringBuilder sb, String refPrefix, List<ResolvedTarget> targets, int startIndex) {
        int index = Math.max(1, startIndex);
        for (ResolvedTarget target : targets) {
            if (target == null) {
                continue;
            }

            sb.append(index).append(") ");
            sb.append("ref=").append(StringUtils.hasText(refPrefix) ? refPrefix.trim() : "target")
                .append("#").append(index).append(" ");

            if (StringUtils.hasText(target.getVectorSpace())) {
                sb.append("vectorSpace=").append(target.getVectorSpace()).append(" ");
            }
            if (StringUtils.hasText(target.getId())) {
                sb.append("id=").append(target.getId()).append(" ");
            }

            if (target.getMetadata() != null && !target.getMetadata().isEmpty()) {
                sb.append(" metadata={");
                String meta = target.getMetadata().entrySet().stream()
                    .filter(e -> e != null && StringUtils.hasText(e.getKey()) && StringUtils.hasText(e.getValue()))
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
                sb.append(meta).append("}");
            }

            if (StringUtils.hasText(target.getContentText())) {
                sb.append(" contentTextTruncated=").append(target.isContentTextTruncated());
                sb.append(" contentText=\"").append(target.getContentText()).append("\"");
            }

            sb.append("\n");
            index++;
        }
        return index;
    }
}
