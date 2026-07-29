package ai.fabric.execution.specialist.manifest;

import java.util.List;

public record SpecialistActionSpec(
    List<String> visible,
    List<String> requestableReads,
    List<String> proposableWrites
) {
    public SpecialistActionSpec {
        visible = visible == null ? List.of() : List.copyOf(visible);
        requestableReads = requestableReads == null
            ? List.of()
            : List.copyOf(requestableReads);
        proposableWrites = proposableWrites == null
            ? List.of()
            : List.copyOf(proposableWrites);
    }
}
