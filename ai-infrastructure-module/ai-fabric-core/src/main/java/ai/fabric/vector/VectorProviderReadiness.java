package ai.fabric.vector;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Production readiness verdict derived from vector provider diagnostics.
 */
public record VectorProviderReadiness(Status status, List<String> reasons, List<String> warnings) {

    public enum Status {
        READY,
        WARN,
        NOT_READY
    }

    public VectorProviderReadiness {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean operational() {
        return status != Status.NOT_READY;
    }

    public boolean productionReady() {
        return status == Status.READY;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status.name());
        map.put("operational", operational());
        map.put("productionReady", productionReady());
        map.put("reasons", reasons);
        map.put("warnings", warnings);
        return Collections.unmodifiableMap(map);
    }
}
