package com.ai.fabric.realapps.behavior.web;

import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.provider.AIProvider;
import com.ai.fabric.examples.smoke.health.DemoHealthContributor;
import com.ai.fabric.realapps.behavior.service.DurableBehaviorAnalysisService;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class BehaviorHealthContributor implements DemoHealthContributor {

    private final SpecialistRegistry specialists;
    private final List<AIProvider> providers;
    private final DataSource dataSource;
    private final Environment environment;

    public BehaviorHealthContributor(
        SpecialistRegistry specialists,
        List<AIProvider> providers,
        DataSource dataSource,
        Environment environment
    ) {
        this.specialists = specialists;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @Override
    public Map<String, Object> details() {
        Optional<RegisteredSpecialist> registration = specialists.findRegistered(
            DurableBehaviorAnalysisService.SPECIALIST_ID
        );
        String providerName = environment.getProperty(
            "ai.providers.llm-provider",
            "unknown"
        );
        boolean providerReady = providers.stream().anyMatch(provider ->
            sameProvider(provider.getProviderName(), providerName)
                && safelyAvailable(provider)
        );
        boolean storageReady = storageReady();
        boolean realProviderRequired = environment.getProperty(
            "app.behavior-demo.analysis.require-real-ai",
            Boolean.class,
            false
        );
        boolean realProviderSelected = !isLocalProvider(providerName);
        boolean specialistReady = registration.isPresent();

        Map<String, Object> specialist = new LinkedHashMap<>();
        specialist.put(
            "id",
            DurableBehaviorAnalysisService.SPECIALIST_ID.toString()
        );
        specialist.put("ready", specialistReady);
        registration.ifPresent(value -> {
            specialist.put("contentHash", value.contentHash());
            specialist.put("source", value.source().name());
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
            "status",
            specialistReady
                && storageReady
                && providerReady
                && (!realProviderRequired || realProviderSelected)
                ? "UP"
                : "DOWN"
        );
        out.put("specialist", Map.copyOf(specialist));
        out.put("provider", Map.of(
            "generation", providerName,
            "ready", providerReady,
            "realProviderRequired", realProviderRequired,
            "realProviderSelected", realProviderSelected
        ));
        out.put("storage", Map.of(
            "domain", storageReady ? "UP" : "DOWN",
            "execution", storageReady ? "UP" : "DOWN"
        ));
        out.put("execution", Map.of(
            "durability", "DURABLE",
            "automaticWrites", false,
            "inputBoundary", "SERVER_OWNED_USER_AND_RAW_EVENTS",
            "sources", List.of("APPLICATION", "SCHEDULED"),
            "scheduledPrincipal", "SYSTEM"
        ));
        out.put("liveFallbackEnabled", false);
        return Map.copyOf(out);
    }

    private boolean sameProvider(String actual, String expected) {
        return actual != null
            && expected != null
            && actual.trim().toLowerCase(Locale.ROOT).equals(
                expected.trim().toLowerCase(Locale.ROOT)
            );
    }

    private boolean isLocalProvider(String providerName) {
        if (providerName == null) {
            return true;
        }
        String normalized = providerName.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("behavior-local") || normalized.equals("smoke");
    }

    private boolean safelyAvailable(AIProvider provider) {
        try {
            return provider.isAvailable()
                && provider.getStatus() != null
                && provider.getStatus().isHealthy();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean storageReady() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (SQLException exception) {
            return false;
        }
    }
}
