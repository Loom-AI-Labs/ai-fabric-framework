package com.ai.fabric.realapps.incident.web;

import ai.fabric.execution.plan.ExecutionPlanRegistry;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.AIProviderManager;
import com.ai.fabric.examples.smoke.health.DemoDeploymentInfoService;
import com.ai.fabric.realapps.incident.execution.IncidentPlans;
import com.ai.fabric.realapps.incident.execution.IncidentSpecialists;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class IncidentHealthController {

    private final DemoDeploymentInfoService deploymentInfo;
    private final SpecialistRegistry specialists;
    private final ExecutionPlanRegistry plans;
    private final AIProviderManager providers;
    private final Environment environment;
    private final DataSource dataSource;

    public IncidentHealthController(
        DemoDeploymentInfoService deploymentInfo,
        SpecialistRegistry specialists,
        ExecutionPlanRegistry plans,
        AIProviderManager providers,
        Environment environment,
        DataSource dataSource
    ) {
        this.deploymentInfo = deploymentInfo;
        this.specialists = specialists;
        this.plans = plans;
        this.providers = providers;
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        List<String> specialistIds = List.of(
            IncidentSpecialists.SERVICE_HEALTH.toString(),
            IncidentSpecialists.CHANGE_RISK.toString(),
            IncidentSpecialists.INTAKE.toString(),
            IncidentSpecialists.CONVERSATION_MANAGER.toString()
        );
        boolean specialistsReady = specialistIds.stream().allMatch(id ->
            specialists.findRegistered(
                ai.fabric.execution.specialist.SpecialistId.parse(id)
            ).isPresent()
        );
        boolean plansReady = plans.find(IncidentPlans.SEQUENTIAL).isPresent()
            && plans.find(IncidentPlans.PARALLEL).isPresent();
        List<Map<String, Object>> specialistHealth = specialistIds.stream()
            .map(id -> specialists.requireRegistered(
                ai.fabric.execution.specialist.SpecialistId.parse(id)
            ))
            .map(registered -> Map.<String, Object>of(
                "id", registered.id().toString(),
                "contentHash", registered.contentHash(),
                "source", registered.source().name(),
                "ready", true
            ))
            .toList();
        List<Map<String, Object>> planHealth = List.of(
            plans.require(IncidentPlans.SEQUENTIAL),
            plans.require(IncidentPlans.PARALLEL)
        ).stream().map(plan -> Map.<String, Object>of(
            "id", plan.id().toString(),
            "contentHash", plan.contentHash(),
            "ready", true
        )).toList();
        String generationProvider = environment.getProperty(
            "ai.providers.llm-provider",
            "unknown"
        );
        AIProvider provider = providers.getProvider(generationProvider);
        boolean providerReady = provider != null
            && provider.isAvailable()
            && provider.getStatus().isHealthy();
        boolean storageReady = storageReady();
        Map<String, Object> out = new java.util.LinkedHashMap<>(
            deploymentInfo.health()
        );
        out.put("status", specialistsReady && plansReady && providerReady
            && storageReady ? "UP" : "DOWN");
        out.put("specialists", specialistHealth);
        out.put("plans", planHealth);
        out.put("specialistsReady", specialistsReady);
        out.put("plansReady", plansReady);
        out.put("provider", Map.of(
            "generation", generationProvider,
            "ready", providerReady
        ));
        out.put("storage", Map.of(
            "domain", storageReady ? "UP" : "DOWN",
            "chat", storageReady ? "UP" : "DOWN",
            "execution", "EPHEMERAL"
        ));
        out.put("fanInPolicy", "ALL_REQUIRED");
        out.put("conversationHistory", "BACKEND_OWNED");
        return Map.copyOf(out);
    }

    private boolean storageReady() {
        try (var connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (java.sql.SQLException exception) {
            return false;
        }
    }
}
