package ai.fabric.execution.specialist.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

final class SpecialistExtensionRegistrySupport<T> {

    private static final Pattern EXACT_ID = Pattern.compile(
        "[a-z][a-z0-9-]{0,79}@[A-Za-z0-9][A-Za-z0-9._-]{0,39}"
    );

    private final Map<String, T> values;
    private final String type;

    SpecialistExtensionRegistrySupport(
        List<T> extensions,
        Function<T, String> idExtractor,
        String type
    ) {
        this.type = Objects.requireNonNull(type, "type is required");
        Map<String, T> registered = new LinkedHashMap<>();
        for (T extension :
            extensions == null ? List.<T>of() : extensions) {
            Objects.requireNonNull(extension, type + " must not be null");
            String id = requireId(idExtractor.apply(extension));
            if (registered.putIfAbsent(id, extension) != null) {
                throw new SpecialistManifestException(
                    "DUPLICATE_EXTENSION_ID",
                    "Duplicate " + type + " extension " + id + ".",
                    "extension:" + id
                );
            }
        }
        this.values = Map.copyOf(registered);
    }

    T require(String id) {
        String normalized = requireId(id);
        T value = values.get(normalized);
        if (value == null) {
            throw new SpecialistManifestException(
                "EXTENSION_REFERENCE_NOT_FOUND",
                "No " + type + " extension is registered for "
                    + normalized + ".",
                "extension:" + normalized
            );
        }
        return value;
    }

    List<T> list() {
        return List.copyOf(values.values());
    }

    static String requireId(String value) {
        String normalized = Objects.requireNonNull(
            value,
            "extension ID is required"
        ).trim();
        if (!EXACT_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "Extension ID must use the exact lowercase-name@version form"
            );
        }
        return normalized;
    }
}
