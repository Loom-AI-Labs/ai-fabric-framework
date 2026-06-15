package ai.fabric.intent;

import ai.fabric.intent.action.ActionInfo;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.prompt.ManagedPromptDefaults;
import ai.fabric.prompt.PromptPreviewOverlaySupport;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts the structured context into a system prompt understood by the LLM.
 */
@Service
@RequiredArgsConstructor
public class EnrichedPromptBuilder {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final String TEMPLATE_FAMILY = "intent-extraction/compound";
    private static final String TEMPLATE_SYSTEM = "system";
    private static final String TEMPLATE_SYSTEM_MANAGED = "system-managed";
    private static final String TEMPLATE_USER = "user";

    private static final String PLACEHOLDER_BEHAVIOR_CONTEXT = "behavior_context_section";
    private static final String PLACEHOLDER_AVAILABLE_ACTIONS = "available_actions_section";
    private static final String PLACEHOLDER_KNOWLEDGE_BASE = "knowledge_base_overview_section";
    private static final String PLACEHOLDER_RELATIONSHIP_QUERY_ENTITY_TYPES_RULE = "relationship_query_entity_types_rule";
    private static final String PLACEHOLDER_MANAGED_SYSTEM_PROMPT = "managed_system_prompt";
    private static final String PLACEHOLDER_MANAGED_INTENT_EXTRACTION_PROMPT = "managed_intent_extraction_prompt";
    private static final String PLACEHOLDER_MANAGED_ACTION_SELECTION_PROMPT = "managed_action_selection_prompt";
    private static final String PLACEHOLDER_MANAGED_CLARIFICATION_PROMPT = "managed_clarification_prompt";
    private static final String PLACEHOLDER_USER_QUERY = "user_query";

    private final SystemContextBuilder systemContextBuilder;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    public String buildSystemPrompt(OrchestrationContext contextInput) {
        SystemContext context = systemContextBuilder.buildContext(contextInput);

        String behavior = buildBehaviorContextSection(context);
        OrchestrationPolicy policy = contextInput != null ? contextInput.getOrchestrationPolicy() : null;
        boolean actionsEnabled = policy == null || policy.capabilities() == null || policy.capabilities().actionsEnabled();
        boolean retrievalEnabled = policy == null || policy.capabilities() == null || policy.capabilities().retrievalEnabled();
        boolean knowledgeBaseOverviewEnabled = policy == null
            || policy.capabilities() == null
            || policy.capabilities().knowledgeBaseOverviewEnabled();

        String actions = actionsEnabled ? buildAvailableActionsSection(context) : "";
        String knowledge = (retrievalEnabled && knowledgeBaseOverviewEnabled) ? buildKnowledgeBaseOverviewSection(context) : "";
        String entityTypesRule = buildRelationshipQueryEntityTypesRule(context);
        Map<String, String> promptOverlay = PromptPreviewOverlaySupport.extract(
            contextInput != null ? contextInput.getMetadata() : null
        );

        String base;
        if (PromptPreviewOverlaySupport.hasAny(
            promptOverlay,
            "systemPrompt",
            "intentExtractionPrompt",
            "actionSelectionPrompt",
            "clarificationPrompt"
        )) {
            base = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM_MANAGED).template(),
                Map.of(
                    PLACEHOLDER_BEHAVIOR_CONTEXT, behavior,
                    PLACEHOLDER_AVAILABLE_ACTIONS, actions,
                    PLACEHOLDER_KNOWLEDGE_BASE, knowledge,
                    PLACEHOLDER_RELATIONSHIP_QUERY_ENTITY_TYPES_RULE, entityTypesRule,
                    PLACEHOLDER_MANAGED_SYSTEM_PROMPT, PromptPreviewOverlaySupport.resolveOverlayValue(
                        promptOverlay,
                        "systemPrompt",
                        ManagedPromptDefaults.SYSTEM_PROMPT
                    ),
                    PLACEHOLDER_MANAGED_INTENT_EXTRACTION_PROMPT, PromptPreviewOverlaySupport.resolveOverlayValue(
                        promptOverlay,
                        "intentExtractionPrompt",
                        ManagedPromptDefaults.INTENT_EXTRACTION_PROMPT
                    ),
                    PLACEHOLDER_MANAGED_ACTION_SELECTION_PROMPT, PromptPreviewOverlaySupport.resolveOverlayValue(
                        promptOverlay,
                        "actionSelectionPrompt",
                        ManagedPromptDefaults.ACTION_SELECTION_PROMPT
                    ),
                    PLACEHOLDER_MANAGED_CLARIFICATION_PROMPT, PromptPreviewOverlaySupport.resolveOverlayValue(
                        promptOverlay,
                        "clarificationPrompt",
                        ManagedPromptDefaults.CLARIFICATION_PROMPT
                    )
                )
            );
        } else {
            base = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM).template(),
                Map.of(
                    PLACEHOLDER_BEHAVIOR_CONTEXT, behavior,
                    PLACEHOLDER_AVAILABLE_ACTIONS, actions,
                    PLACEHOLDER_KNOWLEDGE_BASE, knowledge,
                    PLACEHOLDER_RELATIONSHIP_QUERY_ENTITY_TYPES_RULE, entityTypesRule
                )
            );
        }

        String addon = OrchestrationPolicyPromptConstraints.buildSystemAddon(policy);
        String prompt = base;
        if (StringUtils.hasText(addon)) {
            prompt = prompt.trim() + "\n\n" + addon + "\n";
        }
        return prompt;
    }

    @Deprecated(forRemoval = true)
    public String buildSystemPrompt(String userId) {
        return buildSystemPrompt(OrchestrationContext.forUser(userId));
    }

    public String buildUserPrompt(String userQuery) {
        String safe = userQuery != null ? userQuery : "";
        return promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_USER).template(),
            Map.of(PLACEHOLDER_USER_QUERY, safe)
        );
    }

    private String buildBehaviorContextSection(SystemContext context) {
        if (!context.hasBehaviorContext()) {
            return "";
        }
        return "## USER BEHAVIOR CONTEXT\n"
            + context.getBehaviorContext().toPromptString()
            + "\nUse this context to tailor tone and recommendations (be empathetic for frustrated users, proactive for at-risk users).\n";
    }

    private String buildAvailableActionsSection(SystemContext context) {
        StringBuilder prompt = new StringBuilder(512);
        prompt.append("AVAILABLE ACTIONS:\n");
        if (context.getAvailableActions().isEmpty()) {
            prompt.append("- No actions are currently registered. Use information intents only.\n\n");
            return prompt.toString();
        }

        for (ActionInfo action : context.getAvailableActions()) {
            prompt.append("- ").append(action.getName());
            if (action.getDescription() != null && !action.getDescription().isBlank()) {
                prompt.append(": ").append(action.getDescription());
            }
            if (action.getCategory() != null && !action.getCategory().isBlank()) {
                prompt.append(" [category=").append(action.getCategory()).append("]");
            }
            if (!CollectionUtils.isEmpty(action.getParameterSchemas())) {
                String schemas = action.getParameterSchemas().entrySet().stream()
                    .filter(entry -> entry.getKey() != null)
                    .map(entry -> entry.getKey() + ":" + summarizeSchema(entry.getValue(), 0))
                    .collect(Collectors.joining(", "));
                prompt.append(" (paramsSchema: ").append(schemas).append(")");
            } else if (!action.getParameters().isEmpty()) {
                String parameters = action.getParameters().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
                prompt.append(" (params: ").append(parameters).append(")");
            }
            prompt.append("\n");
        }
        prompt.append("\n");
        return prompt.toString();
    }

    private String summarizeSchema(AIActionParamSchema schema, int depth) {
        if (schema == null || depth > 3) {
            return "unknown";
        }

        AIActionParamType type = schema.getType() != null ? schema.getType() : AIActionParamType.UNKNOWN;
        String suffix = Boolean.TRUE.equals(schema.getRequired()) ? "!" : "";
        String batch = Boolean.TRUE.equals(schema.getBatchTargets()) ? " [batchTargets]" : "";
        String defaultValue = schema.getDefaultValue() != null ? " default=" + schema.getDefaultValue() : "";

        String summary = switch (type) {
            case STRING -> "string" + suffix + batch + defaultValue;
            case INTEGER -> "integer" + suffix + batch + defaultValue;
            case NUMBER -> "number" + suffix + batch + defaultValue;
            case BOOLEAN -> "boolean" + suffix + batch + defaultValue;
            case ARRAY -> {
                String item = schema.getItems() != null ? summarizeSchema(schema.getItems(), depth + 1) : "unknown";
                yield "array<" + item + ">" + suffix + batch + defaultValue;
            }
            case OBJECT -> {
                if (!CollectionUtils.isEmpty(schema.getProperties())) {
                    String props = schema.getProperties().entrySet().stream()
                        .filter(e -> e.getKey() != null)
                        .limit(12)
                        .map(e -> e.getKey() + ":" + summarizeSchema(e.getValue(), depth + 1))
                        .collect(Collectors.joining(", "));
                    yield "object{" + props + "}" + suffix + batch + defaultValue;
                }
                yield "object" + suffix + batch + defaultValue;
            }
            case UNKNOWN -> "unknown" + suffix + batch + defaultValue;
        };
        if (!StringUtils.hasText(schema.getDescription())) {
            return summary;
        }
        String description = schema.getDescription().trim().replaceAll("\\s+", " ");
        if (description.length() > 220) {
            description = description.substring(0, 217) + "...";
        }
        return summary + " - " + description;
    }

    private String buildKnowledgeBaseOverviewSection(SystemContext context) {
        StringBuilder prompt = new StringBuilder(512);
        prompt.append("KNOWLEDGE BASE OVERVIEW:\n");
        KnowledgeBaseOverview overview = context.getKnowledgeBaseOverview();
        if (overview == null) {
            prompt.append("- Total documents: unknown\n");
            prompt.append("- Entity types: unknown\n\n");
            return prompt.toString();
        }

        prompt.append("- Total documents: ").append(overview.getTotalIndexedDocuments()).append("\n");
        if (!overview.getDocumentsByType().isEmpty()) {
            prompt.append("- Documents by type:\n");
            overview.getDocumentsByType().forEach((type, count) ->
                prompt.append("  • ").append(type).append(": ").append(count).append("\n")
            );
        }
        var availableSpaces = overview.getEntityTypes() != null && !overview.getEntityTypes().isEmpty()
            ? overview.getEntityTypes().stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList()
            : overview.getDocumentsByType() != null && !overview.getDocumentsByType().isEmpty()
                ? overview.getDocumentsByType().keySet().stream().filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList()
                : java.util.List.<String>of();
        if (!availableSpaces.isEmpty()) {
            prompt.append("- Available vectorSpace values: ").append(String.join(", ", availableSpaces)).append("\n");
            prompt.append("  • vectorSpace MUST be one of these values (do NOT invent new vectorSpace names). If unsure, omit vectorSpace.\n");
        }
        if (overview.getLastIndexUpdateTime() != null) {
            prompt.append("- Last index update: ").append(TIMESTAMP_FORMATTER.format(overview.getLastIndexUpdateTime())).append("\n");
        }
        prompt.append("\n");
        return prompt.toString();
    }

    private String buildRelationshipQueryEntityTypesRule(SystemContext context) {
        Set<String> types = context != null ? context.getAvailableEntityTypes() : null;
        if (types == null || types.isEmpty()) {
            return "   - Extract entityTypes from the user request as an array of lower-case strings. No entity types are currently registered. Use [] when unknown or when no entity types match.";
        }
        return "   - Extract entityTypes from the user request as an array of lower-case strings. Available entity types: "
            + String.join(", ", types)
            + ". Only use entity types from this list. Use [] when unknown or when no entity types match.";
    }
}
