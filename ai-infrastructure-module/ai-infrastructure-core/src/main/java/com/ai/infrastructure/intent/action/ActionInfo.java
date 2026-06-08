package com.ai.infrastructure.intent.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Metadata describing an actionable capability that can be handled by the orchestration layer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ActionInfo {

    /**
     * Unique identifier for the action (e.g. {@code cancel_subscription}).
     */
    private String name;

    /**
     * Human readable description of what the action does.
     */
    private String description;

    /**
     * Logical grouping (e.g. {@code subscription}, {@code payment}).
     */
    private String category;

    /**
     * Map of parameter names to a human readable description or type.
     */
    @Builder.Default
    private Map<String, String> parameters = Collections.emptyMap();

    /**
     * Structured parameter schema contract (optional).
     */
    @Builder.Default
    private Map<String, AIActionParamSchema> parameterSchemas = Collections.emptyMap();

    /**
     * Optional hint for how the confirmation message should be retrieved from configuration.
     */
    private String confirmationMessageKey;

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters == null ? Collections.emptyMap() : Map.copyOf(parameters);
    }

    public void setParameterSchemas(Map<String, AIActionParamSchema> parameterSchemas) {
        this.parameterSchemas = parameterSchemas == null ? Collections.emptyMap() : Map.copyOf(parameterSchemas);
    }

    public boolean hasValidName() {
        return name != null && !name.isBlank();
    }

    public ActionInfo sanitizedCopy() {
        ActionInfo copy = ActionInfo.builder()
            .name(name != null ? name.trim() : null)
            .description(description != null ? description.trim() : null)
            .category(category != null ? category.trim() : null)
            .confirmationMessageKey(confirmationMessageKey != null ? confirmationMessageKey.trim() : null)
            .parameters(parameters == null ? Collections.emptyMap() : parameters)
            .parameterSchemas(parameterSchemas == null ? Collections.emptyMap() : parameterSchemas)
            .build();
        if (copy.parameters != null && !copy.parameters.isEmpty()) {
            copy.parameters = copy.parameters.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .collect(Collectors.toUnmodifiableMap(
                    entry -> entry.getKey().trim(),
                    entry -> entry.getValue() == null ? "" : entry.getValue().trim()
                ));
        }
        return copy;
    }

    private static final class Collectors {
        private Collectors() {
        }

        static <K, V> java.util.stream.Collector<Map.Entry<K, V>, ?, Map<K, V>> toUnmodifiableMap(
            java.util.function.Function<Map.Entry<K, V>, K> keyMapper,
            java.util.function.Function<Map.Entry<K, V>, V> valueMapper
        ) {
            return java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toMap(keyMapper, valueMapper, (left, right) -> right, java.util.LinkedHashMap::new),
                Collections::unmodifiableMap
            );
        }
    }

    public boolean equalsByName(ActionInfo other) {
        return other != null && Objects.equals(name, other.name);
    }
}
