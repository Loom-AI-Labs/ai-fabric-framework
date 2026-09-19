package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class BoundedJsonSupport {

    private static final Pattern TARGET_FIELD = Pattern.compile(
        "[A-Za-z][A-Za-z0-9_-]{0,99}"
    );
    private static final Set<String> RESERVED_FIELDS = Set.of(
        "__proto__",
        "constructor",
        "prototype"
    );

    private final CanonicalJsonSupport canonicalJson;
    private final SpecialistChainDeclarativeBounds bounds;

    BoundedJsonSupport(
        CanonicalJsonSupport canonicalJson,
        SpecialistChainDeclarativeBounds bounds
    ) {
        this.canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
        this.bounds = Objects.requireNonNull(bounds, "bounds are required");
    }

    String validatePointer(String pointer, String field) {
        String normalized = Objects.requireNonNull(
            pointer,
            field + " is required"
        ).trim();
        if (normalized.length() > bounds.maxPointerCharacters()) {
            throw new IllegalArgumentException(
                field + " exceeds the pointer character limit"
            );
        }
        try {
            JsonPointer compiled = JsonPointer.compile(normalized);
            int depth = 0;
            while (!compiled.matches()) {
                depth++;
                compiled = compiled.tail();
            }
            if (depth > bounds.maxPointerDepth()) {
                throw new IllegalArgumentException(
                    field + " exceeds the pointer depth limit"
                );
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                field + " must be a bounded RFC 6901 JSON Pointer",
                ex
            );
        }
        return normalized;
    }

    String validateTargetField(String value) {
        String normalized = Objects.requireNonNull(
            value,
            "targetField is required"
        ).trim();
        if (!TARGET_FIELD.matcher(normalized).matches()
            || RESERVED_FIELDS.contains(normalized)) {
            throw new IllegalArgumentException(
                "targetField must be a safe top-level JSON property"
            );
        }
        return normalized;
    }

    JsonNode select(
        JsonNode source,
        String pointer,
        boolean required,
        String field
    ) {
        return select(source, pointer, required, field, null);
    }

    JsonNode select(
        JsonNode source,
        String pointer,
        boolean required,
        String field,
        WorkBudget workBudget
    ) {
        Objects.requireNonNull(source, "JSON source is required");
        String validated = validatePointer(pointer, field);
        JsonNode selected = source.at(validated);
        if (selected.isMissingNode() || selected.isNull()) {
            if (required) {
                throw new IllegalArgumentException(
                    field + " does not select a required value"
                );
            }
            return null;
        }
        int work = inspect(selected, field);
        if (workBudget != null) {
            workBudget.consume(work, field);
        }
        return selected.deepCopy();
    }

    int inspect(JsonNode value, String field) {
        NodeStats stats = count(value, 0);
        if (stats.depth() > bounds.maxNodeDepth()) {
            throw new IllegalArgumentException(
                field + " exceeds the selected-node depth limit"
            );
        }
        if (stats.count() > bounds.maxNodeCount()) {
            throw new IllegalArgumentException(
                field + " exceeds the selected-node count limit"
            );
        }
        if (bytes(value) > bounds.maxCopiedValueBytes()) {
            throw new IllegalArgumentException(
                field + " exceeds the selected-value byte limit"
            );
        }
        if (stats.count() > bounds.maxTotalMappingWork()) {
            throw new IllegalArgumentException(
                field + " exceeds the JSON work limit"
            );
        }
        return stats.count();
    }

    WorkBudget newWorkBudget() {
        return new WorkBudget(bounds.maxTotalMappingWork());
    }

    static final class WorkBudget {

        private final int maximum;
        private int consumed;

        private WorkBudget(int maximum) {
            this.maximum = maximum;
        }

        void consume(int units, String field) {
            consumed = Math.addExact(consumed, units);
            if (consumed > maximum) {
                throw new IllegalArgumentException(
                    field + " exceeds the cumulative JSON work limit"
                );
            }
        }
    }

    void validateMappedObject(JsonNode value) {
        NodeStats stats = count(value, 0);
        if (stats.depth() > bounds.maxNodeDepth()
            || stats.count() > bounds.maxNodeCount()
            || stats.count() > bounds.maxTotalMappingWork()
            || bytes(value) > bounds.maxMappedObjectBytes()) {
            throw new IllegalArgumentException(
                "Mapped worker input exceeds a declarative JSON limit"
            );
        }
    }

    private int bytes(JsonNode value) {
        return canonicalJson.write(value)
            .getBytes(StandardCharsets.UTF_8).length;
    }

    private NodeStats count(JsonNode value, int depth) {
        int count = 1;
        int maximumDepth = depth;
        if (depth > bounds.maxNodeDepth()) {
            return new NodeStats(count, maximumDepth);
        }
        if (value.isContainerNode()) {
            for (JsonNode child : value) {
                NodeStats childStats = count(child, depth + 1);
                count += childStats.count();
                maximumDepth = Math.max(
                    maximumDepth,
                    childStats.depth()
                );
                if (count > bounds.maxNodeCount()
                    || count > bounds.maxTotalMappingWork()) {
                    break;
                }
            }
        }
        return new NodeStats(count, maximumDepth);
    }

    private record NodeStats(int count, int depth) {}
}
