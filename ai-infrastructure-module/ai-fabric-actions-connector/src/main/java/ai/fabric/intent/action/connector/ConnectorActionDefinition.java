package ai.fabric.intent.action.connector;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.ActionResultPresentationHint;
import ai.fabric.intent.action.AIContributionProvenance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connector action definition loaded from a file-based contract.
 *
 * <p>This describes the action contract (metadata + parameter schema + confirmation rules),
 * not the execution routing. Execution is performed via the Customer Connector API.</p>
 */
public record ConnectorActionDefinition(
    String name,
    String displayName,
    String description,
    String category,
    ActionAccessMode accessMode,
    boolean requiresConfirmation,
    String confirmationMessage,
    List<ConnectorActionParamDefinition> params,
    boolean anonymousAllowed,
    boolean groundingEligible,
    boolean readActionResolutionEligible,
    ActionResultPresentationHint resultPresentationHint,
    String builtInModuleId,
    String builtInCardId,
    AIContributionProvenance provenance,
    List<ConnectorActionPostPolicyDefinition> postPolicies,
    ConnectorActionLlmFactsDefinition llmFacts,
    String adapterType,
    Map<String, Object> execution,
    Map<String, Object> mcpServers
) {
    public ConnectorActionDefinition {
        params = params != null ? List.copyOf(params) : List.of();
        postPolicies = postPolicies != null ? List.copyOf(postPolicies) : List.of();
        adapterType = adapterType != null && !adapterType.isBlank() ? adapterType.trim() : null;
        execution = execution != null && !execution.isEmpty() ? Map.copyOf(execution) : Map.of();
        mcpServers = mcpServers != null && !mcpServers.isEmpty() ? Map.copyOf(mcpServers) : Map.of();
    }

    public ConnectorActionDefinition(
        String name,
        String displayName,
        String description,
        String category,
        ActionAccessMode accessMode,
        boolean requiresConfirmation,
        String confirmationMessage,
        List<ConnectorActionParamDefinition> params,
        boolean anonymousAllowed,
        boolean groundingEligible,
        boolean readActionResolutionEligible,
        ActionResultPresentationHint resultPresentationHint,
        String builtInModuleId,
        String builtInCardId,
        AIContributionProvenance provenance,
        List<ConnectorActionPostPolicyDefinition> postPolicies,
        ConnectorActionLlmFactsDefinition llmFacts
    ) {
        this(
            name,
            displayName,
            description,
            category,
            accessMode,
            requiresConfirmation,
            confirmationMessage,
            params,
            anonymousAllowed,
            groundingEligible,
            readActionResolutionEligible,
            resultPresentationHint,
            builtInModuleId,
            builtInCardId,
            provenance,
            postPolicies,
            llmFacts,
            null,
            Map.of(),
            Map.of()
        );
    }

    public Map<String, Object> runtimeActionConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (adapterType != null && !adapterType.isBlank()) {
            out.put("adapterType", adapterType);
        }
        if (execution != null && !execution.isEmpty()) {
            out.put("execution", execution);
        }
        if (mcpServers != null && !mcpServers.isEmpty()) {
            out.put("mcpServers", mcpServers);
        }
        if (!out.isEmpty()) {
            if (accessMode != null) {
                out.put("accessMode", accessMode.name());
            }
            if (requiresConfirmation) {
                out.put("requiresConfirmation", true);
            }
        }
        if (!out.isEmpty() && params != null && !params.isEmpty()) {
            out.put("params", params.stream()
                .map(ConnectorActionDefinition::paramRuntimeConfig)
                .toList());
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static Map<String, Object> paramRuntimeConfig(ConnectorActionParamDefinition param) {
        if (param == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        putIfText(out, "name", param.name());
        putIfText(out, "description", param.description());
        putIfPresent(out, "type", paramType(param.type()));
        out.put("required", param.required());
        if (param.batchTargets()) {
            out.put("batchTargets", true);
        }
        putIfText(out, "pattern", param.pattern());
        if (param.allowedValues() != null && !param.allowedValues().isEmpty()) {
            out.put("allowedValues", param.allowedValues());
        }
        if (param.min() != null) {
            out.put("min", param.min());
        }
        if (param.max() != null) {
            out.put("max", param.max());
        }
        if (param.defaultValue() != null) {
            out.put("defaultValue", param.defaultValue());
        }
        putIfText(out, "visibility", param.visibility());
        if (param.askUser() != null) {
            out.put("askUser", param.askUser());
        }
        if (param.resolveFrom() != null && !param.resolveFrom().isEmpty()) {
            out.put("resolveFrom", param.resolveFrom());
        }
        if (param.evidenceBound()) {
            out.put("evidenceBound", true);
        }
        if (param.evidenceKeys() != null && !param.evidenceKeys().isEmpty()) {
            out.put("evidenceKeys", param.evidenceKeys());
        }
        putIfText(out, "evidenceFallbackPolicy", param.evidenceFallbackPolicy());
        if (param.items() != null) {
            out.put("items", paramRuntimeConfig(param.items()));
        }
        if (param.properties() != null && !param.properties().isEmpty()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            param.properties().forEach((name, schema) -> {
                if (name != null && schema != null) {
                    properties.put(name, paramRuntimeConfig(schema));
                }
            });
            if (!properties.isEmpty()) {
                out.put("properties", properties);
            }
        }
        if (param.requiredProperties() != null && !param.requiredProperties().isEmpty()) {
            out.put("requiredProperties", param.requiredProperties());
        }
        return Map.copyOf(out);
    }

    private static String paramType(AIActionParamType type) {
        return type == null ? null : type.name();
    }

    private static void putIfText(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value.trim());
        }
    }

    private static void putIfPresent(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }
}
