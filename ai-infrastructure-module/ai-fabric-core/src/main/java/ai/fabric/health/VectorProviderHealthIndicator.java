package ai.fabric.health;

import ai.fabric.service.VectorManagementService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot Actuator health indicator for vector provider readiness.
 */
public class VectorProviderHealthIndicator implements HealthIndicator {

    private final VectorManagementService vectorManagementService;

    public VectorProviderHealthIndicator(VectorManagementService vectorManagementService) {
        this.vectorManagementService = vectorManagementService;
    }

    @Override
    public Health health() {
        Map<String, Object> diagnostics = vectorManagementService.getProviderDiagnostics();
        Map<String, Object> readiness = readiness(diagnostics);
        String status = String.valueOf(readiness.getOrDefault("status", "NOT_READY"));
        boolean operational = Boolean.TRUE.equals(readiness.get("operational"));

        Health.Builder builder = operational ? Health.up() : Health.down();
        builder.withDetail("readinessStatus", status);
        builder.withDetail("productionReady", Boolean.TRUE.equals(readiness.get("productionReady")));
        builder.withDetail("reasons", readiness.getOrDefault("reasons", List.of()));
        builder.withDetail("warnings", readiness.getOrDefault("warnings", List.of()));
        builder.withDetail("vectorDatabase", diagnostics);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readiness(Map<String, Object> diagnostics) {
        Object readiness = diagnostics != null ? diagnostics.get("readiness") : null;
        if (readiness instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, value) -> typed.put(String.valueOf(key), value));
            return typed;
        }
        return Map.of(
            "status", "NOT_READY",
            "operational", false,
            "productionReady", false,
            "reasons", List.of("Vector provider readiness verdict is missing."),
            "warnings", List.of()
        );
    }
}
