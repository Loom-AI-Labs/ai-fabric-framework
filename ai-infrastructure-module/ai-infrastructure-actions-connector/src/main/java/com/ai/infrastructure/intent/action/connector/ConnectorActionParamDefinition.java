package com.ai.infrastructure.intent.action.connector;

import com.ai.infrastructure.intent.action.AIActionParamType;

import java.util.List;
import java.util.Map;

/**
 * Connector action parameter definition loaded from a file-based contract.
 */
public record ConnectorActionParamDefinition(
    String name,
    String description,
    AIActionParamType type,
    boolean required,
    boolean batchTargets,
    String pattern,
    List<String> allowedValues,
    Long min,
    Long max,
    Object defaultValue,
    String visibility,
    Boolean askUser,
    Map<String, Object> resolveFrom,
    boolean sensitive,
    ConnectorActionParamDefinition items,
    Map<String, ConnectorActionParamDefinition> properties,
    List<String> requiredProperties,
    boolean evidenceBound,
    List<String> evidenceKeys,
    String evidenceFallbackPolicy
) {
    public ConnectorActionParamDefinition {
        allowedValues = allowedValues != null ? List.copyOf(allowedValues) : List.of();
        visibility = visibility != null && !visibility.isBlank() ? visibility.trim() : null;
        resolveFrom = resolveFrom != null ? Map.copyOf(resolveFrom) : Map.of();
        properties = properties != null ? Map.copyOf(properties) : Map.of();
        requiredProperties = requiredProperties != null ? List.copyOf(requiredProperties) : List.of();
        evidenceKeys = evidenceKeys != null ? List.copyOf(evidenceKeys) : List.of();
        evidenceFallbackPolicy = evidenceFallbackPolicy != null && !evidenceFallbackPolicy.isBlank()
            ? evidenceFallbackPolicy.trim()
            : null;
    }
}
