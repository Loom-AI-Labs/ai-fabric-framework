package ai.fabric.execution.gateway;

import java.util.Map;

/**
 * Validated typed output and non-sensitive finalization diagnostics.
 */
public record SpecialistOutputFinalization<O>(
    O output,
    Map<String, Object> diagnostics
) {
    public SpecialistOutputFinalization {
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
