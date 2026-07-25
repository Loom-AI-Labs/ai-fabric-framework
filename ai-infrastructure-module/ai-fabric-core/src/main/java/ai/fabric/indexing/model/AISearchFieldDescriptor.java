package ai.fabric.indexing.model;

import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;

import java.util.Set;

/**
 * Immutable searchable-field contract.
 */
public record AISearchFieldDescriptor(
    AIValueAccessor accessor,
    String name,
    Set<AISearchDestination> destinations,
    AISearchPreprocessing preprocessing,
    int maxLength,
    int priority,
    boolean required,
    int declarationOrder
) {
    public AISearchFieldDescriptor {
        destinations = Set.copyOf(destinations);
    }
}
