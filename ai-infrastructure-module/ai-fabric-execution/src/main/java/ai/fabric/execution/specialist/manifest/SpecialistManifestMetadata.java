package ai.fabric.execution.specialist.manifest;

import java.util.LinkedHashMap;
import java.util.Map;

public record SpecialistManifestMetadata(
    String name,
    String version,
    String displayName,
    String description,
    Map<String, String> labels
) {
    public SpecialistManifestMetadata {
        labels = labels == null || labels.isEmpty()
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(labels));
    }
}
