package ai.fabric.execution.input;

import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.Set;

/**
 * Exact-version application extension for detecting and applying missing factual input.
 *
 * <p>The extension does not authorize execution. The gateway validates its requirement, pauses
 * before provider orchestration, validates the response schema, and reauthorizes on resume.</p>
 */
public interface SpecialistInputContinuation<I> {

    String id();

    Class<I> inputType();

    Set<SpecialistSchemaId> responseSchemas();

    Optional<SpecialistInputRequirement> requiredInput(I input);

    I resume(
        I originalInput,
        SpecialistInputRequirement requirement,
        JsonNode response
    );

    /**
     * Creates the process-local snapshot retained while an invocation waits.
     *
     * <p>Immutable Java records may use the default. Mutable inputs must return a defensive copy.</p>
     */
    default I snapshot(I input) {
        return input;
    }
}
