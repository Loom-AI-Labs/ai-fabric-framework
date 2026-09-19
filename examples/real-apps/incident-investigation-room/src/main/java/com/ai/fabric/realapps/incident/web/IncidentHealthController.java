package com.ai.fabric.realapps.incident.web;

import ai.fabric.execution.chain.SpecialistChainRegistry;
import ai.fabric.execution.chain.manifest.SpecialistChainManifestRuntimeStatus;
import ai.fabric.execution.chain.state.JdbcSpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.SpecialistChainExecutionRepository;
import ai.fabric.execution.plan.ExecutionPlanRegistry;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.AIProviderManager;
import com.ai.fabric.examples.smoke.health.DemoDeploymentInfoService;
import com.ai.fabric.realapps.incident.execution.IncidentPlans;
import com.ai.fabric.realapps.incident.execution.IncidentSpecialistChains;
import com.ai.fabric.realapps.incident.execution.IncidentSpecialists;
import com.ai.fabric.realapps.incident.action.ReadChangeApprovalsActionHandler;
import com.ai.fabric.realapps.incident.action.ReadIncidentAlertsActionHandler;
import com.ai.fabric.realapps.incident.action.ReadRecentDeploymentsActionHandler;
import com.ai.fabric.realapps.incident.action.ReadServiceMetricsActionHandler;
import com.ai.fabric.realapps.incident.service.IncidentEventRepository;
import com.ai.fabric.realapps.incident.service.IncidentRunbookIndexService;
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
    private final SpecialistChainRegistry chains;
    private final SpecialistChainExecutionRepository chainExecutions;
    private final SpecialistChainManifestRuntimeStatus chainManifests;
    private final AIProviderManager providers;
    private final Environment environment;
    private final DataSource dataSource;
    private final AIActionRegistry actions;
    private final IncidentEventRepository events;
    private final IncidentRunbookIndexService runbooks;

    public IncidentHealthController(
        DemoDeploymentInfoService deploymentInfo,
        SpecialistRegistry specialists,
        ExecutionPlanRegistry plans,
        SpecialistChainRegistry chains,
        SpecialistChainExecutionRepository chainExecutions,
        SpecialistChainManifestRuntimeStatus chainManifests,
        AIProviderManager providers,
        Environment environment,
        DataSource dataSource,
        AIActionRegistry actions,
        IncidentEventRepository events,
        IncidentRunbookIndexService runbooks
    ) {
        this.deploymentInfo = deploymentInfo;
        this.specialists = specialists;
        this.plans = plans;
        this.chains = chains;
        this.chainExecutions = chainExecutions;
        this.chainManifests = chainManifests;
        this.providers = providers;
        this.environment = environment;
        this.dataSource = dataSource;
        this.actions = actions;
        this.events = events;
        this.runbooks = runbooks;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        List<String> specialistIds = List.of(
            IncidentSpecialists.SERVICE_HEALTH.toString(),
            IncidentSpecialists.CHANGE_RISK.toString(),
            IncidentSpecialists.INTAKE.toString(),
            IncidentSpecialists.CONVERSATION_MANAGER.toString(),
            IncidentSpecialists.SERVICE_HEALTH_V2.toString(),
            IncidentSpecialists.CHANGE_RISK_V2.toString(),
            IncidentSpecialists.INTAKE_V2.toString(),
            IncidentSpecialists.CONVERSATION_MANAGER_V2.toString(),
            IncidentSpecialists.CHAIN_MANAGER_V3.toString()
        );
        boolean specialistsReady = specialistIds.stream().allMatch(id ->
            specialists.findRegistered(
                ai.fabric.execution.specialist.SpecialistId.parse(id)
            ).isPresent()
        );
        boolean plansReady = plans.find(IncidentPlans.SEQUENTIAL).isPresent()
            && plans.find(IncidentPlans.PARALLEL).isPresent()
            && plans.find(IncidentPlans.SEQUENTIAL_V2).isPresent()
            && plans.find(IncidentPlans.PARALLEL_V2).isPresent();
        boolean chainsReady = chains.find(
            IncidentSpecialistChains.SMART_INVESTIGATION
        ).isPresent() && chains.find(
            IncidentSpecialistChains.DECLARATIVE_INVESTIGATION
        ).isPresent() && chainManifests.ready();
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
            plans.require(IncidentPlans.PARALLEL),
            plans.require(IncidentPlans.SEQUENTIAL_V2),
            plans.require(IncidentPlans.PARALLEL_V2)
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
        List<String> actionNames = List.of(
            ReadServiceMetricsActionHandler.NAME,
            ReadIncidentAlertsActionHandler.NAME,
            ReadRecentDeploymentsActionHandler.NAME,
            ReadChangeApprovalsActionHandler.NAME
        );
        boolean actionsReady = actionNames.stream().allMatch(name ->
            actions.findHandler(name).isPresent()
                && actions.findMetadata(name).isPresent()
        );
        IncidentRunbookIndexService.IndexStatus runbookStatus =
            runbooks.status();
        boolean runbooksReady = "READY".equals(runbookStatus.state())
            && runbookStatus.indexedDocuments() > 0;
        Map<String, Object> out = new java.util.LinkedHashMap<>(
            deploymentInfo.health()
        );
        out.put("status", specialistsReady && plansReady && actionsReady
            && chainsReady && providerReady && storageReady && runbooksReady
            ? "UP" : "DOWN");
        out.put("specialists", specialistHealth);
        out.put("plans", planHealth);
        out.put("chains", chains.list().stream().map(chain ->
            Map.<String, Object>of(
                "id", chain.id().toString(),
                "contentHash", chain.contentHash(),
                "source", chain.source().name(),
                "manager", chain.definition().managerSpecialistId().toString(),
                "targets", chain.definition().targets().stream()
                    .map(target -> target.specialistId().toString())
                    .toList(),
                "ready", true
            )
        ).toList());
        out.put("chainManifests", Map.of(
            "ready", chainManifests.ready(),
            "discovered", chainManifests.discoveredManifestCount(),
            "registered", chainManifests.manifestDefinedCount(),
            "auditHash", chainManifests.auditResourceAggregateHash(),
            "semanticsHash",
            chainManifests.declarativeSemanticsAggregateHash(),
            "executionHash",
            chainManifests.effectiveExecutionAggregateHash()
        ));
        out.put("specialistsReady", specialistsReady);
        out.put("plansReady", plansReady);
        out.put("chainsReady", chainsReady);
        out.put("actions", actionNames.stream().map(name ->
            Map.<String, Object>of(
                "name", name,
                "ready", actions.findHandler(name).isPresent()
                    && actions.findMetadata(name).isPresent(),
                "accessMode", "READ"
            )
        ).toList());
        out.put("actionsReady", actionsReady);
        out.put("provider", Map.of(
            "generation", generationProvider,
            "ready", providerReady
        ));
        out.put("storage", Map.of(
            "domain", storageReady ? "UP" : "DOWN",
            "chat", storageReady ? "UP" : "DOWN",
            "plans", "EPHEMERAL",
            "specialistChains",
            chainExecutions instanceof JdbcSpecialistChainExecutionRepository
                ? "JDBC"
                : "EPHEMERAL"
        ));
        out.put("fanInPolicy", "ALL_REQUIRED");
        out.put("conversationHistory", "BACKEND_OWNED");
        out.put("eventStore", Map.of(
            "type", "IMMUTABLE_IN_MEMORY",
            "totalEvents", events.totalCount(),
            "trustedFiltering", true
        ));
        out.put("runbooks", runbookStatus);
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
