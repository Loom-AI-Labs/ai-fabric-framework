package com.ai.fabric.realapps.agenticresolver.service;

import ai.fabric.execution.action.ActionProposalReceiptRepository;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.manager.ConversationManagerGateway;
import ai.fabric.execution.manager.ConversationManagerRegistry;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.plan.ExecutionPlanRegistry;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistManifestRuntimeStatus;
import ai.fabric.execution.state.DurableExecutionRepository;
import ai.fabric.provider.AIProvider;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountConversationManagers;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolverSpecialists;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DeploymentInfoService {

    private static final String APP_POM_PROPERTIES =
        "META-INF/maven/com.ai.fabric.examples/agentic-ai-action-resolver/pom.properties";
    private static final String BUILD_INFO_FILE = "/app/build-info.properties";

    private final Environment environment;
    private final Instant startedAt;
    private final Properties appProperties;
    private final Properties fileBuildProperties;
    private final List<AIProvider> providers;
    private final AIExecutionGateway executionGateway;
    private final AIInteractiveExecutionGateway interactiveExecutionGateway;
    private final AIExecutionCoordinator executionCoordinator;
    private final ExecutionPlanRegistry executionPlanRegistry;
    private final ConversationManagerGateway conversationManagerGateway;
    private final ConversationManagerRegistry conversationManagerRegistry;
    private final SpecialistRegistry specialistRegistry;
    private final ActionProposalReceiptRepository receiptRepository;
    private final DurableExecutionRepository durableExecutionRepository;
    private final AIExecutionProperties executionProperties;
    private final SpecialistManifestRuntimeStatus manifestRuntimeStatus;

    public DeploymentInfoService(
        Environment environment,
        List<AIProvider> providers,
        AIExecutionGateway executionGateway,
        AIInteractiveExecutionGateway interactiveExecutionGateway,
        AIExecutionCoordinator executionCoordinator,
        ExecutionPlanRegistry executionPlanRegistry,
        ConversationManagerGateway conversationManagerGateway,
        ConversationManagerRegistry conversationManagerRegistry,
        SpecialistRegistry specialistRegistry,
        ActionProposalReceiptRepository receiptRepository,
        Optional<DurableExecutionRepository> durableExecutionRepository,
        AIExecutionProperties executionProperties,
        SpecialistManifestRuntimeStatus manifestRuntimeStatus
    ) {
        this.environment = environment;
        this.startedAt = Instant.now();
        this.appProperties = loadClasspathProperties(APP_POM_PROPERTIES);
        this.fileBuildProperties = loadFileProperties(BUILD_INFO_FILE);
        this.providers = providers != null ? List.copyOf(providers) : List.of();
        this.executionGateway = executionGateway;
        this.interactiveExecutionGateway = interactiveExecutionGateway;
        this.executionCoordinator = executionCoordinator;
        this.executionPlanRegistry = executionPlanRegistry;
        this.conversationManagerGateway = conversationManagerGateway;
        this.conversationManagerRegistry = conversationManagerRegistry;
        this.specialistRegistry = specialistRegistry;
        this.receiptRepository = receiptRepository;
        this.durableExecutionRepository =
            durableExecutionRepository.orElse(null);
        this.executionProperties = executionProperties;
        this.manifestRuntimeStatus = manifestRuntimeStatus;
    }

    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("service", "agentic-ai-action-resolver");
        health.put("version", firstText(
            env("APP_VERSION"),
            appProperties.getProperty("version"),
            "unknown"
        ));
        health.put("aiFabricVersion", firstText(
            env("AI_FABRIC_VERSION"),
            fileBuildProperties.getProperty("aiFabricVersion"),
            "unknown"
        ));
        health.put("commit", firstText(
            env("APP_BUILD_COMMIT"),
            env("COOLIFY_GIT_COMMIT_SHA"),
            env("SOURCE_COMMIT"),
            env("GIT_COMMIT"),
            env("COMMIT_SHA"),
            env("RAILWAY_GIT_COMMIT_SHA"),
            env("git_commit"),
            fileBuildProperties.getProperty("commit"),
            "unknown"
        ));
        health.put("branch", firstText(
            env("APP_BUILD_BRANCH"),
            env("COOLIFY_GIT_BRANCH"),
            env("GIT_BRANCH"),
            env("SOURCE_BRANCH"),
            env("git_branch"),
            fileBuildProperties.getProperty("branch"),
            "unknown"
        ));
        health.put("builtAt", firstText(
            env("APP_BUILD_TIME"),
            env("BUILD_TIME"),
            fileBuildProperties.getProperty("builtAt"),
            "unknown"
        ));
        health.put("providerReadiness", providerReadiness());
        List<String> specialists = specialistRegistry.list().stream()
            .map(definition -> definition.id().toString())
            .sorted()
            .toList();
        List<Map<String, Object>> specialistDefinitions =
            specialistRegistry.listRegistered().stream()
                .sorted(java.util.Comparator.comparing(item ->
                    item.id().toString()
                ))
                .map(this::specialistDefinition)
                .toList();
        AIExecutionProperties.Receipts receipts =
            executionProperties.getReceipts();
        boolean durableAsync =
            executionProperties.getAsync().getRepository()
                == AIExecutionProperties.AsyncRepository.JDBC;
        String asyncDurability = durableAsync
            ? ExecutionDurability.DURABLE.name()
            : ExecutionDurability.EPHEMERAL.name();
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("ready", executionGateway != null);
        execution.put(
            "interactiveDialogueGatewayReady",
            interactiveExecutionGateway != null
        );
        execution.put("planCoordinatorReady", executionCoordinator != null);
        execution.put(
            "conversationManagerReady",
            conversationManagerGateway != null
        );
        execution.put(
            "conversationManagers",
            conversationManagerRegistry.list().stream()
                .map(manager -> Map.of(
                    "id", manager.id().toString(),
                    "contentHash", manager.contentHash(),
                    "managerSpecialist",
                        manager.definition().managerSpecialistId()
                            .toString(),
                    "targets",
                        manager.definition().targets().stream()
                            .map(target ->
                                target.specialistId().toString()
                            )
                            .toList()
                ))
                .toList()
        );
        execution.put(
            "planDurability",
            "EPHEMERAL"
        );
        execution.put(
            "plans",
            executionPlanRegistry.list().stream()
                .map(plan -> Map.of(
                    "id", plan.id().toString(),
                    "contentHash", plan.contentHash(),
                    "steps", plan.definition().steps().size()
                ))
                .toList()
        );
        execution.put("asyncDurability", asyncDurability);
        execution.put(
            "durableAsyncStateReady",
            !durableAsync || durableExecutionRepository != null
        );
        execution.put(
            "writeReceiptDurability",
            receipts.getRepository().name()
        );
        execution.put("writeReceiptsReady", receiptRepository != null);
        execution.put("receiptTtl", receipts.getTtl().toString());
        execution.put(
            "staleExecutingAfter",
            receipts.getStaleExecutingAfter().toString()
        );
        execution.put("receiptCleanupEnabled", receipts.isCleanupEnabled());
        execution.put("receiptRetention", receipts.getRetention().toString());
        execution.put("specialists", specialists);
        execution.put("specialistDefinitions", specialistDefinitions);
        execution.put("manifestRuntime", Map.of(
            "enabled", manifestRuntimeStatus.enabled(),
            "ready", manifestRuntimeStatus.ready(),
            "loadedDefinitionCount",
                manifestRuntimeStatus.loadedDefinitionCount(),
            "manifestDefinitionCount",
                manifestRuntimeStatus.manifestDefinitionCount(),
            "javaDefinitionCount",
                manifestRuntimeStatus.javaDefinitionCount(),
            "registryContentHash",
                manifestRuntimeStatus.registryContentHash()
        ));
        execution.put(
            "accountResolverRegistered",
            specialists.contains(
                AccountResolverSpecialists.SPECIALIST_ID.toString()
            )
        );
        execution.put(
            "accountResolverReadRegistered",
            specialists.contains(
                AccountResolverSpecialists.READ_SPECIALIST_ID
                    .toString()
            )
        );
        execution.put(
            "accountResolutionCoordinatorRegistered",
            specialists.contains(
                AccountResolverSpecialists.DELEGATION_COORDINATOR_ID
                    .toString()
            )
        );
        execution.put(
            "accountResolutionIntakeRegistered",
            specialists.contains(
                AccountResolverSpecialists.HANDOFF_INTAKE_ID.toString()
            )
        );
        execution.put(
            "accountConversationManagerRegistered",
            specialists.contains(
                AccountResolverSpecialists.CONVERSATION_MANAGER_ID
                    .toString()
            ) && conversationManagerRegistry.find(
                AccountConversationManagers.ACCOUNT_RESOLUTION
            ).isPresent()
        );
        execution.put("proactiveEventExecution", Map.of(
            "ready",
                executionGateway != null && specialists.contains(
                    AccountResolverSpecialists.READ_SPECIALIST_ID.toString()
                ) && (!durableAsync || durableExecutionRepository != null),
            "eventType", "PAYMENT_VERIFICATION_FAILED",
            "source", "EVENT",
            "principalType", "SERVICE",
            "durability", asyncDurability,
            "automaticMutation", false
        ));
        health.put("execution", Map.copyOf(execution));
        health.put("startedAt", startedAt.toString());
        health.put("checkedAt", Instant.now().toString());
        return health;
    }

    private Map<String, Object> specialistDefinition(
        RegisteredSpecialist specialist
    ) {
        return Map.of(
            "name", specialist.id().name(),
            "version", specialist.id().version(),
            "source", specialist.source().name(),
            "contentHash", specialist.contentHash()
        );
    }

    private Map<String, Object> providerReadiness() {
        List<String> configured = providers.stream()
            .map(AIProvider::getProviderName)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .sorted()
            .toList();
        List<String> available = new ArrayList<>();
        for (AIProvider provider : providers) {
            try {
                if (provider.isAvailable()) {
                    available.add(provider.getProviderName());
                }
            } catch (RuntimeException ignored) {
                // Readiness reports unavailable rather than hiding or propagating provider failure.
            }
        }
        available.sort(String::compareTo);
        return Map.of(
            "ready", !available.isEmpty(),
            "configuredProviders", configured,
            "availableProviders", List.copyOf(available)
        );
    }

    private String env(String name) {
        String value = environment.getProperty(name);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate) && !"unknown".equalsIgnoreCase(candidate.trim())) {
                return candidate.trim();
            }
        }
        return "unknown";
    }

    private Properties loadClasspathProperties(String location) {
        Properties properties = new Properties();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(location)) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException ignored) {
            // Deployment metadata is diagnostic only; health should remain available.
        }
        return properties;
    }

    private Properties loadFileProperties(String location) {
        Properties properties = new Properties();
        Path path = Path.of(location);
        if (!Files.isRegularFile(path)) {
            return properties;
        }
        try (InputStream inputStream = Files.newInputStream(path)) {
            properties.load(inputStream);
        } catch (IOException ignored) {
            // Deployment metadata is diagnostic only; health should remain available.
        }
        return properties;
    }
}
