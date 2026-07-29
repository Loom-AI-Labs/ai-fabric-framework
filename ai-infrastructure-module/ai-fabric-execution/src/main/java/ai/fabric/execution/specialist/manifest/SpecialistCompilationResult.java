package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.specialist.RegisteredSpecialist;
import java.util.List;
import java.util.Objects;

public record SpecialistCompilationResult(
    RegisteredSpecialist specialist,
    List<SpecialistCompilationDiagnostic> diagnostics
) {
    public SpecialistCompilationResult {
        Objects.requireNonNull(specialist, "specialist is required");
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
