package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.Intent;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.intent.orchestration.targets.ResolvedTargetsContextRenderer;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure helpers for RAG context assembly and vector-space routing metadata.
 */
final class RagContextSupport {

    static final String NO_CONTEXT_MESSAGE = "No relevant context found.";

    private static final int DEFAULT_RAG_GENERATION_MAX_DOCUMENTS = 4;
    private static final int DEFAULT_RAG_GENERATION_MAX_CONTEXT_CHARS = 3_000;

    private RagContextSupport() {
    }

    static String prependPinnedTargetsContext(String ragContext, PipelineContext pipelineContext) {
        if (pipelineContext == null) {
            return ragContext;
        }
        List<ResolvedTarget> targets = pipelineContext.getResolvedTargets();
        if (targets == null || targets.isEmpty()) {
            return ragContext;
        }

        String block = null;
        String pinnedTargetsContext = pipelineContext.getPinnedTargetsContext();
        if (StringUtils.hasText(pinnedTargetsContext) && pinnedTargetsContext.trim().startsWith("PINNED TARGETS")) {
            // Reuse the enrichment-rendered pinned targets block (e.g., "previously pinned") when available.
            // Do NOT reuse the attachments prompt block here, since it has a different contract/format.
            block = pinnedTargetsContext.trim();
        }
        if (!StringUtils.hasText(block)) {
            block = buildPinnedTargetsBlock(targets);
        }
        if (!StringUtils.hasText(block)) {
            return ragContext;
        }
        if (!StringUtils.hasText(ragContext)) {
            return block;
        }
        return block + "\n\n" + ragContext;
    }

    static String buildPinnedTargetsBlock(List<ResolvedTarget> targets) {
        String header = resolvePinnedTargetsHeader(targets);
        return ResolvedTargetsContextRenderer.render(
            header,
            "target",
            targets
        );
    }

    static String resolvePinnedTargetsHeader(List<ResolvedTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return "PINNED TARGETS:";
        }

        ResolvedTargetSource uniform = null;
        for (ResolvedTarget target : targets) {
            if (target == null || target.getSource() == null) {
                return "PINNED TARGETS:";
            }
            if (uniform == null) {
                uniform = target.getSource();
                continue;
            }
            if (uniform != target.getSource()) {
                return "PINNED TARGETS:";
            }
        }

        if (uniform == ResolvedTargetSource.SESSION_METADATA) {
            return "PINNED TARGETS (previously pinned; not current UI selection):";
        }
        if (uniform == ResolvedTargetSource.REQUEST_ATTACHMENTS) {
            return "ATTACHMENTS (user-provided text evidence visible to the assistant; authoritative for this turn):";
        }

        return "PINNED TARGETS (authoritative):";
    }

    static boolean shouldSkipRetrievalForPinnedTargets(Intent intent, PipelineContext pipelineContext) {
        if (intent == null || pipelineContext == null) {
            return false;
        }

        List<ResolvedTarget> targets = pipelineContext.getResolvedTargets();
        if (targets == null || targets.isEmpty()) {
            return false;
        }

        boolean requiresTargetResolution = Boolean.TRUE.equals(intent.getRequiresTargetResolution());
        OrchestrationContext orchContext = pipelineContext.getOrchestrationContext();
        boolean hasRequestAttachments = orchContext != null
            && orchContext.getAttachmentsNormalized() != null
            && !orchContext.getAttachmentsNormalized().isEmpty();

        if (!requiresTargetResolution && !hasRequestAttachments) {
            return false;
        }

        String intentVectorSpace = intent.getVectorSpace();
        if (!StringUtils.hasText(intentVectorSpace)) {
            // vectorSpace is optional. If the model omitted it while still requiring retrieval,
            // do NOT skip retrieval here; allow routing/fan-out to fetch the missing knowledge.
            return false;
        }

        Set<String> targetSpaces = targets.stream()
            .filter(t -> t != null && StringUtils.hasText(t.getVectorSpace()))
            .map(t -> t.getVectorSpace().trim().toLowerCase(java.util.Locale.ROOT))
            .collect(Collectors.toSet());
        if (targetSpaces.isEmpty()) {
            return false;
        }

        List<String> requestedSpaces = parseVectorSpaces(intentVectorSpace).stream()
            .filter(StringUtils::hasText)
            .map(space -> space.trim().toLowerCase(java.util.Locale.ROOT))
            .toList();
        if (requestedSpaces.isEmpty()) {
            return true;
        }

        return requestedSpaces.stream().allMatch(targetSpaces::contains);
    }

    static List<String> parseVectorSpaces(String vectorSpace) {
        if (!StringUtils.hasText(vectorSpace)) {
            return List.of();
        }
        String[] parts = vectorSpace.split(",");
        Set<String> unique = new java.util.LinkedHashSet<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                unique.add(trimmed);
            }
        }
        return List.copyOf(unique);
    }

    static VectorSpaceValidation validateRequestedVectorSpaces(List<String> requestedSpaces,
                                                               AIEntityConfigurationLoader entityConfigurationLoader) {
        if (requestedSpaces == null || requestedSpaces.isEmpty()) {
            return VectorSpaceValidation.empty();
        }

        Set<String> supportedRaw = entityConfigurationLoader != null ? entityConfigurationLoader.getSupportedEntityTypes() : null;
        if (supportedRaw == null || supportedRaw.isEmpty()) {
            // No configured spaces available; don't block routing, just normalize.
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            boolean changed = false;
            for (String space : requestedSpaces) {
                if (!StringUtils.hasText(space)) {
                    changed = true;
                    continue;
                }
                String normalized = space.trim().toLowerCase(java.util.Locale.ROOT);
                changed = changed || !normalized.equals(space);
                if (!unique.add(normalized)) {
                    changed = true;
                }
            }
            return new VectorSpaceValidation(List.copyOf(unique), List.of(), changed);
        }

        Set<String> supported = supportedRaw.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .map(s -> s.toLowerCase(java.util.Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        if (supported.isEmpty()) {
            return VectorSpaceValidation.empty();
        }

        List<String> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        boolean changed = false;

        for (String space : requestedSpaces) {
            if (!StringUtils.hasText(space)) {
                changed = true;
                continue;
            }
            String normalized = space.trim().toLowerCase(java.util.Locale.ROOT);
            changed = changed || !normalized.equals(space);

            if (!seen.add(normalized)) {
                changed = true;
                continue;
            }

            if (supported.contains(normalized)) {
                valid.add(normalized);
            } else {
                invalid.add(normalized);
                changed = true;
            }
        }

        return new VectorSpaceValidation(List.copyOf(valid), List.copyOf(invalid), changed);
    }

    record VectorSpaceValidation(List<String> valid, List<String> invalid, boolean normalizedOrFiltered) {
        static VectorSpaceValidation empty() {
            return new VectorSpaceValidation(List.of(), List.of(), false);
        }

        boolean hasInvalid() {
            return invalid != null && !invalid.isEmpty();
        }
    }

    static RAGResponse.RAGDocument tagDocumentWithVectorSpace(RAGResponse.RAGDocument doc, String vectorSpace) {
        if (doc == null) {
            return null;
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
            meta.putAll(doc.getMetadata());
        }
        meta.put("vectorSpace", vectorSpace);

        return RAGResponse.RAGDocument.builder()
            .id(doc.getId())
            .content(doc.getContent())
            .title(doc.getTitle())
            .type(doc.getType())
            .score(doc.getScore())
            .similarity(doc.getSimilarity())
            .metadata(Collections.unmodifiableMap(meta))
            .embeddings(doc.getEmbeddings())
            .highlightedContent(doc.getHighlightedContent())
            .source(doc.getSource())
            .url(doc.getUrl())
            .createdAt(doc.getCreatedAt())
            .modifiedAt(doc.getModifiedAt())
            .author(doc.getAuthor())
            .tags(doc.getTags())
            .wordCount(doc.getWordCount())
            .language(doc.getLanguage())
            .build();
    }

    static Double bestDocumentScore(List<RAGResponse.RAGDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return null;
        }
        Double best = null;
        for (RAGResponse.RAGDocument doc : docs) {
            if (doc == null) {
                continue;
            }
            Double score = doc.getScore() != null ? doc.getScore() : doc.getSimilarity();
            if (score == null) {
                continue;
            }
            if (best == null || score > best) {
                best = score;
            }
        }
        return best;
    }

    static String buildContextFromDocuments(List<RAGResponse.RAGDocument> documents) {
        return buildContextFromDocuments(documents, null);
    }

    static String buildGenerationContext(List<RAGResponse.RAGDocument> documents,
                                         String fallbackContext,
                                         OrchestrationPolicy.RagBudgets ragBudgets) {
        List<RAGResponse.RAGDocument> safeDocuments = documents != null ? documents : List.of();
        Integer maxContextChars = resolveGenerationContextMaxChars(ragBudgets);
        if (!safeDocuments.isEmpty()) {
            int docsForContext = Math.min(resolveGenerationContextDocumentLimit(ragBudgets), safeDocuments.size());
            return buildContextFromDocuments(safeDocuments.subList(0, docsForContext), maxContextChars);
        }
        if (!StringUtils.hasText(fallbackContext)) {
            return fallbackContext;
        }
        if (maxContextChars == null || maxContextChars <= 0 || fallbackContext.length() <= maxContextChars) {
            return fallbackContext;
        }
        return fallbackContext.substring(0, maxContextChars);
    }

    static int resolveGenerationContextDocumentLimit(OrchestrationPolicy.RagBudgets ragBudgets) {
        if (ragBudgets != null
            && ragBudgets.maxDocumentsUsedForContext() != null
            && ragBudgets.maxDocumentsUsedForContext() > 0) {
            return ragBudgets.maxDocumentsUsedForContext();
        }
        return DEFAULT_RAG_GENERATION_MAX_DOCUMENTS;
    }

    static Integer resolveGenerationContextMaxChars(OrchestrationPolicy.RagBudgets ragBudgets) {
        if (ragBudgets != null && ragBudgets.maxContextChars() != null && ragBudgets.maxContextChars() > 0) {
            return ragBudgets.maxContextChars();
        }
        return DEFAULT_RAG_GENERATION_MAX_CONTEXT_CHARS;
    }

    static String buildContextFromDocuments(List<RAGResponse.RAGDocument> documents, Integer maxChars) {
        if (documents == null || documents.isEmpty()) {
            return NO_CONTEXT_MESSAGE;
        }
        int effectiveMaxChars = maxChars != null && maxChars > 0 ? maxChars : Integer.MAX_VALUE;
        StringBuilder builder = new StringBuilder();
        for (RAGResponse.RAGDocument doc : documents) {
            if (doc == null) {
                continue;
            }
            String vectorSpace = null;
            if (doc.getMetadata() != null) {
                Object vs = doc.getMetadata().get("vectorSpace");
                if (vs instanceof String vsText && StringUtils.hasText(vsText)) {
                    vectorSpace = vsText.trim();
                }
            }

            if (StringUtils.hasText(vectorSpace) || StringUtils.hasText(doc.getId())) {
                builder.append("[");
                if (StringUtils.hasText(vectorSpace)) {
                    builder.append("vectorSpace=").append(vectorSpace);
                }
                if (StringUtils.hasText(doc.getId())) {
                    if (StringUtils.hasText(vectorSpace)) {
                        builder.append(" ");
                    }
                    builder.append("id=").append(doc.getId().trim());
                }
                builder.append("]\n");
            }
            if (StringUtils.hasText(doc.getTitle())) {
                builder.append(doc.getTitle()).append("\n");
            }
            if (StringUtils.hasText(doc.getContent())) {
                builder.append(doc.getContent()).append("\n");
            }
            builder.append("---\n");

            if (builder.length() >= effectiveMaxChars) {
                break;
            }
        }
        String out = builder.toString();
        if (out.length() <= effectiveMaxChars) {
            return out;
        }
        return out.substring(0, effectiveMaxChars);
    }
}
