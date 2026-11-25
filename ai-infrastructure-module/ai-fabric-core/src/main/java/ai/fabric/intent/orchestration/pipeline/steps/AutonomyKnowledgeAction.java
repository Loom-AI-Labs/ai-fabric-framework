package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.RAGRequest;
import ai.fabric.intent.action.*;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.spi.RAGProvider;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** The existing authorized RAG provider exposed as a model-selectable READ tool. */
@Component
public final class AutonomyKnowledgeAction implements AIActionRegistryContributor {
    public static final String NAME = "ai_search_knowledge";
    private final OrchestrationProperties properties;
    private final ObjectProvider<RAGProvider> providers;
    public AutonomyKnowledgeAction(OrchestrationProperties properties, ObjectProvider<RAGProvider> providers) {
        this.properties = properties; this.providers = providers;
    }
    public List<AIActionHandler> getHandlers() {
        return properties.getAutonomy().isEnabled() ? List.of(new Handler()) : List.of();
    }
    private final class Handler implements AIActionHandler {
        private final AIActionMetaData metadata = AIActionMetaData.builder().name(NAME).displayName("Search approved knowledge")
            .description("Search one source from the published knowledgeSources catalogue. Supply a focused query; reformulate it when no useful matches are returned. A sole authorized source is selected automatically. Never ask the visitor for an internal domain.")
            .category("knowledge").accessMode(ActionAccessMode.READ).sideEffectLevel(ActionSideEffectLevel.NONE)
            .anonymousAllowed(true).confirmationRequired(false).groundingEligible(true).readActionResolutionEligible(false)
            .requiredParameters(Set.of("query")).parameterSchemas(Map.of(
                "query", AIActionParamSchema.builder().name("query").type(AIActionParamType.STRING).required(true).build(),
                "vectorSpace", AIActionParamSchema.builder().name("vectorSpace").type(AIActionParamType.STRING)
                    .description("An exact source from knowledgeSources; optional only when exactly one source is authorized.").build())).build();
        public AIActionMetaData getActionMetadata() { return metadata; }
        public boolean requiresConfirmation() { return false; }
        public String getConfirmationMessage(Map<String, Object> parameters, ActionContext context) { return null; }
        private EffectiveCapabilityProfile profile(ActionContext context) {
            return context == null || context.orchestrationContext() == null ? null
                : context.orchestrationContext().getEffectiveCapabilityProfile();
        }
        public boolean validateActionAllowed(ActionContext context) {
            var profile = profile(context);
            var execution = context == null || context.orchestrationContext() == null ? null
                : context.orchestrationContext().getWorkflowExecution();
            if (execution == null || !execution.authority().continueWithinRequest() || profile == null
                || !profile.retrievalEnabled() || profile.effectiveVectorSpaces().isEmpty()) return false;
            Object source = context.actionParams().get("vectorSpace");
            return source == null || source instanceof String text && profile.effectiveVectorSpaces().contains(text);
        }
        public ActionResult executeAction(Map<String, Object> parameters, ActionContext context) {
            var profile = profile(context);
            String source = parameters.get("vectorSpace") instanceof String text && !text.isBlank() ? text : null;
            if (source == null && profile.effectiveVectorSpaces().size() == 1) source = profile.effectiveVectorSpaces().iterator().next();
            if (source == null) return ActionResult.builder().success(false).errorCode("KNOWLEDGE_SOURCE_SELECTION_REQUIRED").build();
            if (!profile.effectiveVectorSpaces().contains(source)) throw new SecurityException("KNOWLEDGE_SOURCE_DENIED");
            var provider = providers.getIfAvailable();
            if (provider == null) return ActionResult.builder().success(false).errorCode("KNOWLEDGE_PROVIDER_UNAVAILABLE").build();
            var budgets = profile.ragBudgets();
            int limit = budgets.maxDocumentsReturnedToClient() == null ? 5 : budgets.maxDocumentsReturnedToClient();
            if (budgets.maxDocumentsUsedForContext() != null) limit = Math.min(limit, budgets.maxDocumentsUsedForContext());
            var request = RAGRequest.builder().query(String.valueOf(parameters.get("query"))).entityType(source)
                .retrievalProfile(provider.sharedRetrievalProfile()).limit(limit)
                .threshold(budgets.similarityThreshold() == null ? 0.6 : budgets.similarityThreshold())
                .authContext(OrchestrationAuthContextResolver.from(context.orchestrationContext()))
                .metadata(Map.of("source", "orchestrator", "workflowOperationId", context.orchestrationContext().getWorkflowOperationId())).build();
            var result = provider.performRAGQuery(request);
            if (result == null || !Boolean.TRUE.equals(result.getSuccess()))
                return ActionResult.builder().success(false).errorCode("KNOWLEDGE_SEARCH_FAILED").build();
            var documents = new ArrayList<Map<String, Object>>();
            int remaining = budgets.maxContextChars() == null ? 12000 : budgets.maxContextChars();
            for (var document : result.getDocuments() == null ? List.<ai.fabric.dto.RAGResponse.RAGDocument>of() : result.getDocuments()) {
                if (documents.size() >= limit || remaining <= 0) break;
                var item = new LinkedHashMap<String, Object>();
                String content = document.getContent() == null ? "" : document.getContent();
                int length = Math.min(remaining, content.length()); remaining -= length;
                item.put("id", document.getId()); item.put("content", content.substring(0, length));
                item.put("title", document.getTitle()); item.put("type", document.getType());
                item.put("score", document.getScore()); item.put("metadata", document.getMetadata());
                if (length < content.length()) item.put("contentTruncated", true);
                item.values().removeIf(Objects::isNull); documents.add(item);
            }
            return ActionResult.builder().success(true).data(ActionPayload.object(Map.of("documents", documents,
                "count", documents.size(), "vectorSpace", source))).build();
        }
        public Optional<Map<String, Object>> buildPostActionLlmFacts(ActionResult result, ActionContext context) {
            return result == null || !result.isSuccess() || result.getData() == null ? Optional.empty() : Optional.of(result.getData().toMap());
        }
        public ActionResult handleError(Exception failure, ActionContext context) {
            return ActionResult.builder().success(false).errorCode("KNOWLEDGE_SEARCH_FAILED").build();
        }
    }
}
