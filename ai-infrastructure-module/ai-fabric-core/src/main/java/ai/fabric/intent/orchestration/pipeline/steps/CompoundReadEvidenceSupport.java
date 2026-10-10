package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.NextStepRecommendation;
import ai.fabric.dto.RAGResponse;
import ai.fabric.dto.ResponseGenerationProfile;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionTargetRef;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.RagResponseGenerationSupport.ResponseGenerationTrace;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Collects successful read-only compound children into one bounded evidence set and performs one
 * outward answer generation. Child results remain available for audit, while clients receive a
 * complete top-level information result instead of an arbitrarily promoted child.
 */
final class CompoundReadEvidenceSupport {

    private static final int DEFAULT_MAX_RETURNED_DOCUMENTS = 10;
    private static final int MIN_OBLIGATION_CONTEXT_CHARS = 240;
    private static final int MIN_DOCUMENT_CONTEXT_CHARS = 160;
    private static final Set<String> CONTEXT_QUERY_STOP_WORDS = Set.of(
        "and", "are", "can", "does", "for", "from", "have", "how", "into", "its", "the", "their",
        "this", "those", "was", "what", "when", "where", "which", "who", "with", "would", "your"
    );
    private static final String ERROR_CODE_ACTION_NOT_FOUND = "ACTION_NOT_FOUND";
    private static final String ERROR_CODE_GENERATION_FAILED = "GENERATION_FAILED";
    private CompoundReadEvidenceSupport() {
    }

    static OrchestrationResult synthesize(List<Intent> intents,
                                          List<OrchestrationResult> children,
                                          List<NextStepRecommendation> nextSteps,
                                          PipelineContext pipelineContext,
                                          RagResponseGenerationSupport generationSupport,
                                          boolean generationEnabled) {
        if (!canFinalize(intents, children)) {
            return null;
        }

        List<ObligationEvidence> obligations = collectObligations(intents, children);
        List<DocumentEvidence> returnedDocumentEvidence = roundRobinDocuments(
            obligations,
            resolveReturnedDocumentLimit(pipelineContext),
            false
        );
        List<RAGResponse.RAGDocument> documents = returnedDocumentEvidence.stream()
            .map(DocumentEvidence::document)
            .toList();
        List<Map<String, Object>> actions = collectPublicActions(obligations);
        Map<String, Object> readActionResolution = collectReadActionResolutionDiagnostics(obligations);
        List<Map<String, Object>> sources = collectSources(documents);

        String query = resolveQuery(pipelineContext);
        int generationDocumentLimit = RagContextSupport.resolveGenerationContextDocumentLimit(
            resolveRagBudgets(pipelineContext)
        );
        List<DocumentEvidence> generationDocumentCandidates = roundRobinDocuments(
            obligations,
            generationDocumentLimit,
            true
        );
        GenerationContext generationContext = buildGenerationContext(
            obligations,
            generationDocumentCandidates,
            pipelineContext
        );
        Intent synthesisIntent = synthesisIntent(intents);

        ResponseGenerationTrace generationTrace = null;
        String answer = null;
        String generationError = null;
        boolean synthesisAttempted = false;
        boolean synthesisPerformed = false;
        if (generationEnabled && StringUtils.hasText(query)) {
            synthesisAttempted = true;
            try {
                generationTrace = generationSupport.generateRagAnswer(
                    synthesisIntent,
                    query,
                    generationContext.content(),
                    pipelineContext
                );
                answer = generationTrace != null ? generationTrace.content() : null;
                synthesisPerformed = StringUtils.hasText(answer);
                if (!synthesisPerformed) {
                    generationError = "Final synthesis returned no content.";
                }
            } catch (Exception ex) {
                generationError = StringUtils.hasText(ex.getMessage())
                    ? ex.getMessage()
                    : "Final synthesis failed.";
            }
        } else if (generationEnabled) {
            generationError = "Final synthesis requires a nonblank original query.";
        }

        String message = StringUtils.hasText(answer)
            ? answer
            : generationEnabled
                ? "The evidence was collected, but the final answer could not be generated."
                : "Search completed.";

        Map<String, Object> diagnostics = buildDiagnostics(
            obligations,
            generationContext.usedDocuments(),
            returnedDocumentEvidence.size(),
            pipelineContext,
            generationContext.content().length(),
            synthesisAttempted,
            synthesisPerformed,
            generationEnabled,
            generationError
        );
        Map<String, Object> metadata = new LinkedHashMap<>(
            generationSupport.responseGenerationMetadata(generationTrace)
        );
        metadata.put("compoundEvidence", diagnostics);
        if (!readActionResolution.isEmpty()) {
            metadata.put("readActionResolution", readActionResolution);
        }

        RAGResponse ragResponse = RAGResponse.builder()
            .documents(documents)
            .totalDocuments(documents.size())
            .usedDocuments(generationContext.usedDocuments().size())
            .success(generationError == null)
            .originalQuery(query)
            .entityType(joinEffectiveVectorSpaces(obligations))
            .metadata(Map.of("compoundEvidence", true))
            .build();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", answer);
        data.put("documents", documents);
        data.put("sources", sources);
        data.put("actions", actions);
        data.put("ragResponse", ragResponse);
        data.put("requiresGeneration", true);
        data.put("results", children);
        data.put("compoundEvidence", diagnostics);
        if (!readActionResolution.isEmpty()) {
            data.put("readActionResolution", readActionResolution);
        }
        if (StringUtils.hasText(generationError)) {
            data.put("generationError", generationError);
        }

        OrchestrationResult result = OrchestrationResult.builder()
            .type(generationError == null
                ? OrchestrationResultType.INFORMATION_PROVIDED
                : OrchestrationResultType.ERROR)
            .success(generationError == null)
            .message(message)
            .errorCode(generationError == null ? null : "GENERATION_FAILED")
            .data(Collections.unmodifiableMap(data))
            .children(Collections.unmodifiableList(new ArrayList<>(children)))
            .nextSteps(nextSteps == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(nextSteps)))
            .metadata(Collections.unmodifiableMap(metadata))
            .internalPinnedTargets(collectPinnedTargets(children))
            .build();
        return result;
    }

    private static boolean canFinalize(List<Intent> intents, List<OrchestrationResult> children) {
        if (intents == null || children == null || intents.size() < 2 || intents.size() != children.size()) {
            return false;
        }
        boolean completedChildPresent = false;
        for (OrchestrationResult child : children) {
            if (child == null || child.getType() == null) {
                return false;
            }
            if (child.getType() == OrchestrationResultType.INFORMATION_PROVIDED
                || child.getType() == OrchestrationResultType.ACTION_EXECUTED) {
                completedChildPresent = completedChildPresent || child.isSuccess();
                continue;
            }
            if (!isSoftChildFailure(child)) {
                return false;
            }
        }
        return completedChildPresent;
    }

    private static boolean isSoftChildFailure(OrchestrationResult child) {
        if (child == null || child.getType() != OrchestrationResultType.ERROR) {
            return false;
        }
        if (ERROR_CODE_ACTION_NOT_FOUND.equals(child.getErrorCode())
            || ERROR_CODE_GENERATION_FAILED.equals(child.getErrorCode())) {
            return true;
        }
        return child.getData() != null && child.getData().containsKey("generationError");
    }

    private static List<ObligationEvidence> collectObligations(List<Intent> intents,
                                                                List<OrchestrationResult> children) {
        List<ObligationEvidence> obligations = new ArrayList<>(intents.size());
        for (int index = 0; index < intents.size(); index++) {
            Intent intent = intents.get(index);
            OrchestrationResult child = children.get(index);
            boolean successful = child != null && child.isSuccess();
            List<RAGResponse.RAGDocument> documents = successful ? extractDocuments(child) : List.of();
            List<ActionEvidence> actionEvidence = successful ? extractActionEvidence(child) : List.of();
            List<Map<String, Object>> publicActions = successful ? extractPublicActions(child) : List.of();
            obligations.add(new ObligationEvidence(
                index,
                intent,
                child,
                documents,
                actionEvidence,
                publicActions
            ));
        }
        return List.copyOf(obligations);
    }

    private static List<RAGResponse.RAGDocument> extractDocuments(OrchestrationResult child) {
        if (child == null || child.getData() == null) {
            return List.of();
        }
        Object value = child.getData().get("documents");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<RAGResponse.RAGDocument> documents = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof RAGResponse.RAGDocument document) {
                documents.add(document);
            }
        }
        return documents.isEmpty() ? List.of() : List.copyOf(documents);
    }

    private static List<ActionEvidence> extractActionEvidence(OrchestrationResult child) {
        if (child == null || child.getData() == null) {
            return List.of();
        }
        Object resolution = child.getData().get("readActionResolution");
        if (!(resolution instanceof Map<?, ?> resolutionMap)) {
            return List.of();
        }
        Object executed = resolutionMap.get("executedActions");
        if (!(executed instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        List<ActionEvidence> evidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String action = stringValue(map.get("action"));
            String summary = stringValue(map.get("evidenceSummary"));
            boolean usable = Boolean.TRUE.equals(map.get("groundingUsable"));
            boolean success = !Boolean.FALSE.equals(map.get("success"));
            if (!usable || !success || !StringUtils.hasText(summary)) {
                continue;
            }
            String executionId = stringValue(map.get("actionExecutionId"));
            String key = String.valueOf(action) + '\u0000' + summary;
            if (seen.add(key)) {
                evidence.add(new ActionEvidence(action, summary, executionId));
            }
        }
        return evidence.isEmpty() ? List.of() : List.copyOf(evidence);
    }

    private static List<Map<String, Object>> extractPublicActions(OrchestrationResult child) {
        if (child == null || !child.isSuccess() || child.getData() == null || child.getData().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        Object actionList = child.getData().get("actions");
        if (actionList instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> action = copyStringKeyMap(item);
                if (!action.isEmpty()) {
                    actions.add(Collections.unmodifiableMap(action));
                }
            }
        }
        if (actions.isEmpty() && StringUtils.hasText(stringValue(child.getData().get("action")))) {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("action", stringValue(child.getData().get("action")));
            Object actionResult = child.getData().get("actionResult");
            if (actionResult instanceof ActionResult) {
                action.put("actionResult", actionResult);
            } else if (actionResult instanceof Map<?, ?>) {
                action.put("actionResult", actionResult);
            }
            String actionExecutionId = stringValue(child.getData().get("actionExecutionId"));
            if (StringUtils.hasText(actionExecutionId)) {
                action.put("actionExecutionId", actionExecutionId);
            }
            actions.add(Collections.unmodifiableMap(action));
        }
        return actions.isEmpty() ? List.of() : List.copyOf(actions);
    }

    private static Map<String, Object> copyStringKeyMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key instanceof String text && item != null) {
                copy.put(text, item);
            }
        });
        return copy;
    }

    private static List<DocumentEvidence> roundRobinDocuments(List<ObligationEvidence> obligations,
                                                              int limit,
                                                              boolean rankForGeneration) {
        if (obligations == null || obligations.isEmpty() || limit <= 0) {
            return List.of();
        }
        Map<Integer, List<DocumentEvidence>> documentsByObligation = new LinkedHashMap<>();
        for (ObligationEvidence obligation : obligations) {
            List<DocumentEvidence> evidence = obligation.documents().stream()
                .map(document -> new DocumentEvidence(obligation.index(), document))
                .toList();
            documentsByObligation.put(
                obligation.index(),
                rankForGeneration ? rankDocumentEvidence(evidence, intentQuery(obligation.intent())) : evidence
            );
        }
        List<DocumentEvidence> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int offset = 0;
        boolean found;
        do {
            found = false;
            for (ObligationEvidence obligation : obligations) {
                List<DocumentEvidence> evidence = documentsByObligation.getOrDefault(obligation.index(), List.of());
                if (evidence.size() <= offset) {
                    continue;
                }
                found = true;
                DocumentEvidence candidate = evidence.get(offset);
                RAGResponse.RAGDocument document = candidate.document();
                String key = documentKey(document);
                if (seen.add(key)) {
                    selected.add(candidate);
                    if (selected.size() >= limit) {
                        return List.copyOf(selected);
                    }
                }
            }
            offset++;
        } while (found);
        return selected.isEmpty() ? List.of() : List.copyOf(selected);
    }

    private static String documentKey(RAGResponse.RAGDocument document) {
        if (document == null) {
            return "null";
        }
        String vectorSpace = documentVectorSpace(document);
        if (StringUtils.hasText(document.getId())) {
            return vectorSpace + "\u0000id\u0000" + document.getId().trim();
        }
        return vectorSpace + "\u0000content\u0000"
            + String.valueOf(document.getSource()) + "\u0000"
            + String.valueOf(document.getUrl()) + "\u0000"
            + String.valueOf(document.getContent());
    }

    private static String documentVectorSpace(RAGResponse.RAGDocument document) {
        if (document != null && document.getMetadata() != null) {
            String value = stringValue(document.getMetadata().get("vectorSpace"));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return document != null && StringUtils.hasText(document.getType()) ? document.getType().trim() : "";
    }

    private static List<Map<String, Object>> collectPublicActions(List<ObligationEvidence> obligations) {
        List<Map<String, Object>> actions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ObligationEvidence obligation : obligations) {
            for (Map<String, Object> action : obligation.publicActions()) {
                String key = String.valueOf(action.get("action")) + "\u0000" + String.valueOf(action.get("actionResult"));
                if (seen.add(key)) {
                    actions.add(action);
                }
            }
        }
        return actions.isEmpty() ? List.of() : List.copyOf(actions);
    }

    private static Map<String, Object> collectReadActionResolutionDiagnostics(
        List<ObligationEvidence> obligations
    ) {
        if (obligations == null || obligations.isEmpty()) {
            return Map.of();
        }

        boolean attempted = false;
        boolean useRag = false;
        List<Map<String, Object>> executedActions = new ArrayList<>();
        List<Map<String, Object>> intentResolutions = new ArrayList<>();
        Set<String> seenExecutions = new LinkedHashSet<>();
        long groundingUsableActionCount = 0;
        long insufficientActionEvidenceCount = 0;

        for (ObligationEvidence obligation : obligations) {
            if (obligation == null || obligation.child() == null || obligation.child().getData() == null) {
                continue;
            }
            Object rawResolution = obligation.child().getData().get("readActionResolution");
            if (!(rawResolution instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
                continue;
            }
            Map<String, Object> resolution = copyStringKeyMap(rawMap);
            attempted = attempted || Boolean.TRUE.equals(resolution.get("attempted"));
            useRag = useRag || Boolean.TRUE.equals(resolution.get("useRag"));

            Map<String, Object> intentResolution = new LinkedHashMap<>();
            intentResolution.put("intentIndex", obligation.index());
            copyIfPresent(resolution, intentResolution, "attempted");
            copyIfPresent(resolution, intentResolution, "skipReason");
            copyIfPresent(resolution, intentResolution, "planningMode");
            copyIfPresent(resolution, intentResolution, "ragCooperationMode");
            copyIfPresent(resolution, intentResolution, "executedActionsCount");
            copyIfPresent(resolution, intentResolution, "groundingUsableActionCount");
            copyIfPresent(resolution, intentResolution, "insufficientActionEvidenceCount");
            copyIfPresent(resolution, intentResolution, "useRag");
            intentResolutions.add(Collections.unmodifiableMap(intentResolution));

            Object rawExecuted = resolution.get("executedActions");
            if (!(rawExecuted instanceof List<?> actionList)) {
                continue;
            }
            int actionIndex = 0;
            for (Object rawAction : actionList) {
                Map<String, Object> action = copyStringKeyMap(rawAction);
                if (action.isEmpty()) {
                    actionIndex++;
                    continue;
                }
                String executionId = stringValue(action.get("actionExecutionId"));
                String deduplicationKey = StringUtils.hasText(executionId)
                    ? "execution\u0000" + executionId
                    : "intent\u0000" + obligation.index() + "\u0000" + actionIndex + "\u0000"
                        + String.valueOf(action.get("action"));
                actionIndex++;
                if (!seenExecutions.add(deduplicationKey)) {
                    continue;
                }
                Map<String, Object> projected = new LinkedHashMap<>(action);
                projected.putIfAbsent("intentIndex", obligation.index());
                executedActions.add(Collections.unmodifiableMap(projected));
                if (Boolean.TRUE.equals(action.get("groundingUsable"))) {
                    groundingUsableActionCount++;
                } else {
                    insufficientActionEvidenceCount++;
                }
            }
        }

        if (intentResolutions.isEmpty() && executedActions.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("enabled", true);
        diagnostics.put("attempted", attempted);
        diagnostics.put("compound", true);
        diagnostics.put("intentResolutions", Collections.unmodifiableList(intentResolutions));
        diagnostics.put("executedActions", Collections.unmodifiableList(executedActions));
        diagnostics.put("executedActionsCount", executedActions.size());
        diagnostics.put("groundingUsableActionCount", groundingUsableActionCount);
        diagnostics.put("insufficientActionEvidenceCount", insufficientActionEvidenceCount);
        diagnostics.put("useRag", useRag);
        return Collections.unmodifiableMap(diagnostics);
    }

    private static void copyIfPresent(Map<String, Object> source,
                                      Map<String, Object> target,
                                      String key) {
        if (source != null && target != null && source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private static List<Map<String, Object>> collectSources(List<RAGResponse.RAGDocument> documents) {
        List<Map<String, Object>> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RAGResponse.RAGDocument document : documents) {
            if (document == null) {
                continue;
            }
            String sourceId = firstText(
                metadataText(document, "knowledgeSourceId"),
                metadataText(document, "sourceId"),
                document.getSource(),
                metadataText(document, "source")
            );
            String key = String.valueOf(sourceId) + "\u0000" + documentKey(document);
            if (!seen.add(key)) {
                continue;
            }
            Map<String, Object> source = new LinkedHashMap<>();
            if (StringUtils.hasText(sourceId)) {
                source.put("sourceId", sourceId);
            }
            if (StringUtils.hasText(document.getId())) {
                source.put("documentId", document.getId());
            }
            if (StringUtils.hasText(document.getTitle())) {
                source.put("title", document.getTitle());
            }
            if (StringUtils.hasText(document.getUrl())) {
                source.put("url", document.getUrl());
            }
            String vectorSpace = documentVectorSpace(document);
            if (StringUtils.hasText(vectorSpace)) {
                source.put("vectorSpace", vectorSpace);
            }
            sources.add(Collections.unmodifiableMap(source));
        }
        return sources.isEmpty() ? List.of() : List.copyOf(sources);
    }

    private static String metadataText(RAGResponse.RAGDocument document, String key) {
        return document != null && document.getMetadata() != null
            ? stringValue(document.getMetadata().get(key))
            : null;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static GenerationContext buildGenerationContext(List<ObligationEvidence> obligations,
                                                            List<DocumentEvidence> selectedDocuments,
                                                            PipelineContext pipelineContext) {
        int maxChars = RagContextSupport.resolveGenerationContextMaxChars(resolveRagBudgets(pipelineContext));
        StringBuilder context = new StringBuilder(Math.min(maxChars, 4096));
        List<DocumentEvidence> usedDocuments = new ArrayList<>();
        appendWithinBudget(context, """
            COMPOUND READ EVIDENCE POLICY
            Answer every clause of the original request from the matching obligation evidence below.
            Explicitly answer each requested fact and qualifier preserved in every Requested clause; do not replace a specific term, condition, amount, distance, status, or requirement with a generic summary.
            Keep live read-action facts and retrieved documents distinct but use both when relevant.
            If an obligation has no sufficient evidence, say which requested part could not be grounded.
            Do not claim that a source was absent when that obligation returned evidence.

            """, maxChars);

        String pinnedTargets = RagContextSupport.prependPinnedTargetsContext(null, pipelineContext);
        if (StringUtils.hasText(pinnedTargets)) {
            appendWithinBudget(context, truncate(pinnedTargets, Math.max(0, maxChars / 4)) + "\n\n", maxChars);
        }

        Map<Integer, List<DocumentEvidence>> documentsByObligation = new LinkedHashMap<>();
        for (DocumentEvidence selected : selectedDocuments) {
            documentsByObligation.computeIfAbsent(selected.obligationIndex(), ignored -> new ArrayList<>())
                .add(selected);
        }

        for (int i = 0; i < obligations.size() && context.length() < maxChars; i++) {
            ObligationEvidence obligation = obligations.get(i);
            int remainingObligations = Math.max(1, obligations.size() - i);
            int remainingChars = Math.max(0, maxChars - context.length());
            int obligationBudget = Math.max(
                MIN_OBLIGATION_CONTEXT_CHARS,
                remainingChars / remainingObligations
            );
            obligationBudget = Math.min(obligationBudget, remainingChars);
            ObligationContext block = buildObligationContext(
                obligation,
                documentsByObligation.getOrDefault(obligation.index(), List.of()),
                obligationBudget
            );
            appendWithinBudget(context, block.content(), maxChars);
            usedDocuments.addAll(block.usedDocuments());
        }

        return new GenerationContext(
            context.length() == 0 ? RagContextSupport.NO_CONTEXT_MESSAGE : context.toString(),
            usedDocuments.isEmpty() ? List.of() : List.copyOf(usedDocuments)
        );
    }

    private static ObligationContext buildObligationContext(ObligationEvidence obligation,
                                                            List<DocumentEvidence> documents,
                                                            int maxChars) {
        StringBuilder block = new StringBuilder();
        block.append("OBLIGATION ").append(obligation.index() + 1).append('\n');
        String query = intentQuery(obligation.intent());
        if (StringUtils.hasText(query)) {
            block.append("Requested clause: ").append(query).append('\n');
        }
        if (obligation.intent() != null && StringUtils.hasText(obligation.intent().getVectorSpace())) {
            block.append("Effective knowledge domains: ").append(obligation.intent().getVectorSpace()).append('\n');
        }

        if (!obligation.child().isSuccess()) {
            block.append("This obligation could not be completed. Do not infer an answer for it.\n\n");
            return new ObligationContext(truncate(block.toString(), maxChars), List.of());
        }

        int availableForEvidence = Math.max(0, maxChars - block.length() - 2);
        boolean hasActions = !obligation.actionEvidence().isEmpty();
        boolean hasDocuments = documents != null && !documents.isEmpty();
        int actionBudget = hasActions
            ? hasDocuments ? Math.max(MIN_OBLIGATION_CONTEXT_CHARS, availableForEvidence / 3) : availableForEvidence
            : 0;
        actionBudget = Math.min(actionBudget, availableForEvidence);
        if (hasActions && actionBudget > 0) {
            appendWithinBudget(
                block,
                buildActionEvidenceContext(obligation.actionEvidence(), actionBudget),
                maxChars
            );
        }

        List<DocumentEvidence> usedDocuments = List.of();
        if (hasDocuments && block.length() < maxChars) {
            String sectionLabel = "Retrieved document evidence:\n";
            int remaining = Math.max(0, maxChars - block.length() - sectionLabel.length());
            BoundedDocumentContext documentContext = buildBoundedDocumentContext(documents, query, remaining);
            if (StringUtils.hasText(documentContext.content())) {
                appendWithinBudget(block, sectionLabel, maxChars);
                appendWithinBudget(block, documentContext.content(), maxChars);
                usedDocuments = documentContext.usedDocuments();
            }
        }
        if (!hasActions && !hasDocuments) {
            block.append("No sufficient grounded evidence was returned for this obligation.\n");
        }
        appendWithinBudget(block, "\n", maxChars);
        return new ObligationContext(truncate(block.toString(), maxChars), usedDocuments);
    }

    private static String buildActionEvidenceContext(List<ActionEvidence> actions, int maxChars) {
        if (actions == null || actions.isEmpty() || maxChars <= 0) {
            return "";
        }
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < actions.size() && context.length() < maxChars; index++) {
            ActionEvidence action = actions.get(index);
            int remainingActions = actions.size() - index;
            int itemBudget = Math.max(1, (maxChars - context.length()) / remainingActions);
            StringBuilder item = new StringBuilder("Read action evidence");
            if (StringUtils.hasText(action.action())) {
                item.append(" (").append(action.action()).append(')');
            }
            item.append(":\n").append(action.summary()).append('\n');
            String rendered = truncate(item.toString(), Math.max(1, itemBudget - 1));
            appendWithinBudget(context, rendered, maxChars);
            appendWithinBudget(context, "\n", maxChars);
        }
        return context.toString();
    }

    private static BoundedDocumentContext buildBoundedDocumentContext(List<DocumentEvidence> documents,
                                                                      String query,
                                                                      int maxChars) {
        if (documents == null || documents.isEmpty() || maxChars <= 0) {
            return new BoundedDocumentContext("", List.of());
        }

        int documentCount = Math.min(
            documents.size(),
            Math.max(1, maxChars / MIN_DOCUMENT_CONTEXT_CHARS)
        );
        List<DocumentEvidence> candidates = rankDocumentEvidence(documents, query).subList(0, documentCount);
        StringBuilder context = new StringBuilder();
        List<DocumentEvidence> usedDocuments = new ArrayList<>();

        for (int index = 0; index < candidates.size() && context.length() < maxChars; index++) {
            int remainingDocuments = candidates.size() - index;
            int documentBudget = Math.max(1, (maxChars - context.length()) / remainingDocuments);
            DocumentEvidence evidence = candidates.get(index);
            String rendered = renderDocumentEvidence(evidence.document(), query, documentBudget);
            if (!StringUtils.hasText(rendered)) {
                continue;
            }
            appendWithinBudget(context, rendered, maxChars);
            usedDocuments.add(evidence);
        }

        return new BoundedDocumentContext(
            context.toString(),
            usedDocuments.isEmpty() ? List.of() : List.copyOf(usedDocuments)
        );
    }

    private static List<DocumentEvidence> rankDocumentEvidence(List<DocumentEvidence> documents,
                                                               String query) {
        if (documents == null || documents.size() < 2) {
            return documents == null ? List.of() : List.copyOf(documents);
        }
        List<String> terms = queryTerms(query);
        if (terms.isEmpty()) {
            return List.copyOf(documents);
        }

        List<RankedDocumentEvidence> ranked = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            DocumentEvidence evidence = documents.get(index);
            ranked.add(new RankedDocumentEvidence(
                evidence,
                documentRelevanceScore(evidence != null ? evidence.document() : null, terms),
                index
            ));
        }
        ranked.sort(
            Comparator.comparingInt(RankedDocumentEvidence::relevanceScore).reversed()
                .thenComparingInt(RankedDocumentEvidence::originalIndex)
        );
        return ranked.stream().map(RankedDocumentEvidence::evidence).toList();
    }

    private static int documentRelevanceScore(RAGResponse.RAGDocument document,
                                              List<String> queryTerms) {
        if (document == null || queryTerms == null || queryTerms.isEmpty()) {
            return 0;
        }
        int score = 0;
        score += matchedTermScore(document.getTitle(), queryTerms, 8);
        score += matchedTermScore(document.getHighlightedContent(), queryTerms, 5);
        score += matchedTermScore(document.getContent(), queryTerms, 2);
        return score;
    }

    private static int matchedTermScore(String value,
                                        List<String> queryTerms,
                                        int weight) {
        if (!StringUtils.hasText(value) || queryTerms == null || queryTerms.isEmpty()) {
            return 0;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : queryTerms) {
            if (normalized.contains(term)) {
                score += weight;
            }
        }
        return score;
    }

    private static String renderDocumentEvidence(RAGResponse.RAGDocument document,
                                                 String query,
                                                 int maxChars) {
        if (document == null || maxChars <= 0) {
            return "";
        }
        StringBuilder header = new StringBuilder();
        String vectorSpace = documentVectorSpace(document);
        if (StringUtils.hasText(vectorSpace) || StringUtils.hasText(document.getId())) {
            header.append('[');
            if (StringUtils.hasText(vectorSpace)) {
                header.append("vectorSpace=").append(vectorSpace);
            }
            if (StringUtils.hasText(document.getId())) {
                if (StringUtils.hasText(vectorSpace)) {
                    header.append(' ');
                }
                header.append("id=").append(document.getId().trim());
            }
            header.append("]\n");
        }
        if (StringUtils.hasText(document.getTitle())) {
            header.append(document.getTitle().trim()).append('\n');
        }

        int excerptBudget = Math.max(0, maxChars - header.length() - 5);
        String sourceText = StringUtils.hasText(document.getHighlightedContent())
            ? document.getHighlightedContent()
            : document.getContent();
        String excerpt = selectRelevantExcerpt(sourceText, query, excerptBudget);
        if (!StringUtils.hasText(excerpt) && header.length() == 0) {
            return "";
        }

        StringBuilder rendered = new StringBuilder(header);
        if (StringUtils.hasText(excerpt)) {
            rendered.append(excerpt.trim()).append('\n');
        }
        rendered.append("---\n");
        return truncate(rendered.toString(), maxChars);
    }

    private static String selectRelevantExcerpt(String content, String query, int maxChars) {
        if (!StringUtils.hasText(content) || maxChars <= 0) {
            return "";
        }
        String normalizedContent = content.trim();
        if (normalizedContent.length() <= maxChars) {
            return normalizedContent;
        }

        List<String> queryTerms = queryTerms(query);
        List<TextSegment> segments = textSegments(normalizedContent, queryTerms);
        if (segments.isEmpty() || queryTerms.isEmpty()) {
            return centeredExcerpt(normalizedContent, queryTerms, maxChars);
        }

        segments.sort(
            Comparator.comparingInt(TextSegment::score).reversed()
                .thenComparingInt(TextSegment::index)
        );
        StringBuilder excerpt = new StringBuilder();
        for (TextSegment segment : segments) {
            if (segment.score() <= 0 || excerpt.length() >= maxChars) {
                break;
            }
            int remaining = maxChars - excerpt.length();
            String value = centeredExcerpt(segment.text(), queryTerms, remaining);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            if (excerpt.length() > 0 && remaining > 1) {
                excerpt.append('\n');
                remaining--;
            }
            appendWithinBudget(excerpt, value, maxChars);
        }
        return excerpt.length() > 0
            ? excerpt.toString()
            : centeredExcerpt(normalizedContent, queryTerms, maxChars);
    }

    private static List<TextSegment> textSegments(String content, List<String> queryTerms) {
        String[] values = content.split("(?:\\R\\s*\\R+|(?<=[.!?])\\s+)");
        List<TextSegment> segments = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            String value = values[index] != null ? values[index].trim() : "";
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : queryTerms) {
                if (normalized.contains(term)) {
                    score++;
                }
            }
            segments.add(new TextSegment(index, value, score));
        }
        return segments;
    }

    private static List<String> queryTerms(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String value : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (value.length() >= 3 && !CONTEXT_QUERY_STOP_WORDS.contains(value)) {
                terms.add(value);
            }
        }
        return terms.isEmpty() ? List.of() : List.copyOf(terms);
    }

    private static String centeredExcerpt(String content, List<String> queryTerms, int maxChars) {
        if (!StringUtils.hasText(content) || maxChars <= 0) {
            return "";
        }
        String value = content.trim();
        if (value.length() <= maxChars) {
            return value;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        int matchIndex = -1;
        for (String term : queryTerms) {
            int candidate = normalized.indexOf(term);
            if (candidate >= 0 && (matchIndex < 0 || candidate < matchIndex)) {
                matchIndex = candidate;
            }
        }
        int start = matchIndex < 0 ? 0 : Math.max(0, matchIndex - Math.max(0, maxChars / 3));
        start = Math.min(start, Math.max(0, value.length() - maxChars));
        int end = Math.min(value.length(), start + maxChars);
        String excerpt = value.substring(start, end);
        if (start > 0 && excerpt.length() > 3) {
            excerpt = "..." + excerpt.substring(3);
        }
        if (end < value.length() && excerpt.length() > 3) {
            excerpt = excerpt.substring(0, excerpt.length() - 3) + "...";
        }
        return excerpt;
    }

    private static void appendWithinBudget(StringBuilder target, String value, int maxChars) {
        if (!StringUtils.hasText(value) || target.length() >= maxChars) {
            return;
        }
        int available = maxChars - target.length();
        target.append(value, 0, Math.min(value.length(), available));
    }

    private static Map<String, Object> buildDiagnostics(List<ObligationEvidence> obligations,
                                                        List<DocumentEvidence> usedDocuments,
                                                        int returnedDocumentCount,
                                                        PipelineContext pipelineContext,
                                                        int generationContextChars,
                                                        boolean synthesisAttempted,
                                                        boolean synthesisPerformed,
                                                        boolean generationEnabled,
                                                        String generationError) {
        Map<Integer, Long> usedDocumentsByObligation = new LinkedHashMap<>();
        for (DocumentEvidence usedDocument : usedDocuments) {
            usedDocumentsByObligation.merge(usedDocument.obligationIndex(), 1L, Long::sum);
        }
        Map<Integer, Map<String, Object>> routingEvents = routingEventsByIntent(pipelineContext);
        List<Map<String, Object>> obligationDiagnostics = new ArrayList<>();
        int completed = 0;
        int empty = 0;
        int failed = 0;
        for (ObligationEvidence obligation : obligations) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("intentIndex", obligation.index());
            item.put("intentType", obligation.intent() != null && obligation.intent().getType() != null
                ? obligation.intent().getType().name()
                : "UNKNOWN");
            String query = intentQuery(obligation.intent());
            if (StringUtils.hasText(query)) {
                item.put("optimizedQuery", query);
            }
            List<String> vectorSpaces = RagContextSupport.parseVectorSpaces(
                obligation.intent() != null ? obligation.intent().getVectorSpace() : null
            );
            item.put("effectiveVectorSpaces", vectorSpaces);
            item.put("documentsRetrieved", obligation.documents().size());
            item.put("documentsUsed", usedDocumentsByObligation.getOrDefault(obligation.index(), 0L));
            item.put("readActionEvidenceCount", obligation.actionEvidence().size());
            item.put("actionCount", obligation.publicActions().size());
            item.put("searchedSourceIds", obligationSourceIds(obligation));
            Map<String, Object> routingEvent = routingEvents.get(obligation.index());
            if (routingEvent != null) {
                Object strategy = routingEvent.get("strategy");
                item.put("routingSource", routingSource(strategy));
                if (strategy != null) {
                    item.put("routingStrategy", strategy);
                }
                Object suggested = routingEvent.get("priorVectorSpace");
                if (suggested instanceof String text && StringUtils.hasText(text)) {
                    item.put("llmSuggestedVectorSpaces", RagContextSupport.parseVectorSpaces(text));
                }
            } else {
                item.put("routingSource", "LLM");
            }
            boolean childFailed = !obligation.child().isSuccess();
            boolean evidenceAvailable = !obligation.documents().isEmpty() || !obligation.actionEvidence().isEmpty();
            item.put("evidenceAvailable", evidenceAvailable);
            item.put("evidenceStatus", childFailed ? "FAILED" : evidenceAvailable ? "SUFFICIENT" : "EMPTY");
            if (childFailed) {
                failed++;
            } else if (evidenceAvailable) {
                completed++;
            } else {
                empty++;
            }
            item.put("childType", obligation.child().getType().name());
            item.put("childSuccess", obligation.child().isSuccess());
            if (StringUtils.hasText(obligation.child().getErrorCode())) {
                item.put("childErrorCode", obligation.child().getErrorCode());
            }
            obligationDiagnostics.add(Collections.unmodifiableMap(item));
        }

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("strategy", "COLLECT_THEN_SYNTHESIZE");
        diagnostics.put("obligationCount", obligations.size());
        diagnostics.put("completedObligationCount", completed);
        diagnostics.put("emptyObligationCount", empty);
        diagnostics.put("deniedObligationCount", 0);
        diagnostics.put("failedObligationCount", failed);
        diagnostics.put("actionExecutionIds", collectActionExecutionIds(obligations));
        diagnostics.put("returnedDocumentCount", returnedDocumentCount);
        diagnostics.put("usedDocumentCount", usedDocuments.size());
        diagnostics.put("generationContextChars", generationContextChars);
        diagnostics.put("obligations", Collections.unmodifiableList(obligationDiagnostics));
        diagnostics.put("generationEnabled", generationEnabled);
        diagnostics.put("finalSynthesisAttempted", synthesisAttempted);
        diagnostics.put("finalSynthesisPerformed", synthesisPerformed);
        diagnostics.put("childGenerationDeferred", true);
        if (StringUtils.hasText(generationError)) {
            diagnostics.put("finalSynthesisError", generationError);
        }
        return Collections.unmodifiableMap(diagnostics);
    }

    private static List<String> collectActionExecutionIds(List<ObligationEvidence> obligations) {
        LinkedHashSet<String> executionIds = new LinkedHashSet<>();
        for (ObligationEvidence obligation : obligations) {
            for (ActionEvidence evidence : obligation.actionEvidence()) {
                if (StringUtils.hasText(evidence.executionId())) {
                    executionIds.add(evidence.executionId());
                }
            }
            for (Map<String, Object> action : obligation.publicActions()) {
                String executionId = stringValue(action.get("actionExecutionId"));
                if (StringUtils.hasText(executionId)) {
                    executionIds.add(executionId);
                }
            }
        }
        return executionIds.isEmpty() ? List.of() : List.copyOf(executionIds);
    }

    private static Map<Integer, Map<String, Object>> routingEventsByIntent(PipelineContext pipelineContext) {
        if (pipelineContext == null || pipelineContext.getMetadata() == null) {
            return Map.of();
        }
        Object value = pipelineContext.getMetadata().get("vectorSpaceRouting");
        if (!(value instanceof List<?> events)) {
            return Map.of();
        }
        Map<Integer, Map<String, Object>> byIntent = new LinkedHashMap<>();
        for (Object rawEvent : events) {
            Map<String, Object> event = copyStringKeyMap(rawEvent);
            Object index = event.get("intentIndex");
            if (index instanceof Number number) {
                byIntent.put(number.intValue(), Collections.unmodifiableMap(event));
            }
        }
        return byIntent.isEmpty() ? Map.of() : Collections.unmodifiableMap(byIntent);
    }

    private static String routingSource(Object strategyValue) {
        String strategy = strategyValue != null ? String.valueOf(strategyValue) : "";
        if ("TRUSTED_SERVER_HINT".equals(strategy)) {
            return "TRUSTED_SERVER_HINT";
        }
        if (strategy.startsWith("LLM") || "NORMALIZED".equals(strategy) || "INVALID_FILTERED".equals(strategy)) {
            return "LLM";
        }
        return "POLICY_FALLBACK";
    }

    private static List<String> obligationSourceIds(ObligationEvidence obligation) {
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        for (RAGResponse.RAGDocument document : obligation.documents()) {
            String source = firstText(
                metadataText(document, "knowledgeSourceId"),
                metadataText(document, "sourceId"),
                document != null ? document.getSource() : null
            );
            if (StringUtils.hasText(source)) {
                sourceIds.add(source);
            }
        }
        collectRagResponseSourceIds(obligation.child(), sourceIds);
        return sourceIds.isEmpty() ? List.of() : List.copyOf(sourceIds);
    }

    private static void collectRagResponseSourceIds(OrchestrationResult child,
                                                    Set<String> sourceIds) {
        if (child == null || child.getData() == null || sourceIds == null) {
            return;
        }
        Object rawResponse = child.getData().get("ragResponse");
        if (!(rawResponse instanceof RAGResponse response) || response.getMetadata() == null) {
            return;
        }
        Object rawDiagnostics = response.getMetadata().get("searchSourceDiagnostics");
        if (!(rawDiagnostics instanceof List<?> diagnostics)) {
            return;
        }
        for (Object rawDiagnostic : diagnostics) {
            Map<String, Object> diagnostic = copyStringKeyMap(rawDiagnostic);
            String sourceId = stringValue(diagnostic.get("sourceId"));
            if (StringUtils.hasText(sourceId) && !"knowledge-source-registry".equals(sourceId)) {
                sourceIds.add(sourceId);
            }
        }
    }

    private static String joinEffectiveVectorSpaces(List<ObligationEvidence> obligations) {
        LinkedHashSet<String> vectorSpaces = new LinkedHashSet<>();
        for (ObligationEvidence obligation : obligations) {
            vectorSpaces.addAll(RagContextSupport.parseVectorSpaces(
                obligation.intent() != null ? obligation.intent().getVectorSpace() : null
            ));
        }
        return vectorSpaces.isEmpty() ? null : String.join(",", vectorSpaces);
    }

    private static List<ActionTargetRef> collectPinnedTargets(List<OrchestrationResult> children) {
        Map<String, ActionTargetRef> targets = new LinkedHashMap<>();
        for (OrchestrationResult child : children) {
            if (child == null || child.getInternalPinnedTargets() == null) {
                continue;
            }
            for (ActionTargetRef target : child.getInternalPinnedTargets()) {
                if (target == null || !StringUtils.hasText(target.id())) {
                    continue;
                }
                String vectorSpace = StringUtils.hasText(target.vectorSpace()) ? target.vectorSpace().trim() : "";
                targets.putIfAbsent(vectorSpace + "\u0000" + target.id().trim(), target);
            }
        }
        return targets.isEmpty() ? List.of() : List.copyOf(targets.values());
    }

    private static Intent synthesisIntent(List<Intent> intents) {
        ResponseGenerationProfile profile = ResponseGenerationProfile.STANDARD;
        for (Intent intent : intents) {
            if (intent == null || intent.getResponseProfile() == null) {
                continue;
            }
            if (intent.getResponseProfile() == ResponseGenerationProfile.DEEP) {
                profile = ResponseGenerationProfile.DEEP;
                break;
            }
            if (intent.getResponseProfile() == ResponseGenerationProfile.STANDARD) {
                profile = ResponseGenerationProfile.STANDARD;
            } else if (profile != ResponseGenerationProfile.STANDARD) {
                profile = ResponseGenerationProfile.CONCISE;
            }
        }
        return Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compound_read_synthesis")
            .requiresGeneration(true)
            .responseProfile(profile)
            .build();
    }

    private static String resolveQuery(PipelineContext pipelineContext) {
        if (pipelineContext == null) {
            return null;
        }
        if (StringUtils.hasText(pipelineContext.getOriginalQuery())) {
            return pipelineContext.getOriginalQuery().trim();
        }
        return StringUtils.hasText(pipelineContext.getEffectiveQuery())
            ? pipelineContext.getEffectiveQuery().trim()
            : null;
    }

    private static String intentQuery(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (StringUtils.hasText(intent.getOptimizedQuery())) {
            return intent.getOptimizedQuery().trim();
        }
        if (StringUtils.hasText(intent.getIntent())) {
            return intent.getIntent().trim();
        }
        if (StringUtils.hasText(intent.getAction())) {
            return intent.getAction().trim();
        }
        return null;
    }

    private static int resolveReturnedDocumentLimit(PipelineContext context) {
        OrchestrationPolicy.RagBudgets budgets = resolveRagBudgets(context);
        if (budgets != null
            && budgets.maxDocumentsReturnedToClient() != null
            && budgets.maxDocumentsReturnedToClient() > 0) {
            return budgets.maxDocumentsReturnedToClient();
        }
        return DEFAULT_MAX_RETURNED_DOCUMENTS;
    }

    private static OrchestrationPolicy.RagBudgets resolveRagBudgets(PipelineContext context) {
        return context != null && context.getOrchestrationPolicy() != null
            ? context.getOrchestrationPolicy().ragBudgets()
            : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text.trim() : null;
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private record ActionEvidence(String action, String summary, String executionId) {
    }

    private record ObligationEvidence(int index,
                                      Intent intent,
                                      OrchestrationResult child,
                                      List<RAGResponse.RAGDocument> documents,
                                      List<ActionEvidence> actionEvidence,
                                      List<Map<String, Object>> publicActions) {
    }

    private record DocumentEvidence(int obligationIndex, RAGResponse.RAGDocument document) {
    }

    private record RankedDocumentEvidence(
        DocumentEvidence evidence,
        int relevanceScore,
        int originalIndex
    ) {
    }

    private record GenerationContext(String content, List<DocumentEvidence> usedDocuments) {
    }

    private record ObligationContext(String content, List<DocumentEvidence> usedDocuments) {
    }

    private record BoundedDocumentContext(String content, List<DocumentEvidence> usedDocuments) {
    }

    private record TextSegment(int index, String text, int score) {
    }
}
