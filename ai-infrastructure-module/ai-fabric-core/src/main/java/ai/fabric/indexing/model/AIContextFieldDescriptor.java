package ai.fabric.indexing.model;

import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;

import java.util.Set;

/**
 * Immutable context-field contract.
 */
public record AIContextFieldDescriptor(
    AIValueAccessor accessor,
    String key,
    AIContextDataType dataType,
    String format,
    Set<AIContextDestination> destinations,
    String description,
    int priority,
    boolean required,
    boolean sanitizePII,
    int declarationOrder
) {
    public AIContextFieldDescriptor {
        destinations = Set.copyOf(destinations);
        format = format == null ? "" : format;
        description = description == null ? "" : description;
    }
}
