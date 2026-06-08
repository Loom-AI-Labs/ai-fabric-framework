package com.ai.fabric.runtime.config;

import com.ai.infrastructure.access.AIAccessControlService;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.datasync.controller.DataSyncController;
import com.ai.infrastructure.datasync.service.DataSyncService;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.service.VectorManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Set;

@Component
public class RuntimeDataSyncDiagnosticsLogger {

    private static final Logger log = LoggerFactory.getLogger(RuntimeDataSyncDiagnosticsLogger.class);
    private static final String VECTOR_SPACES_PATH = "/api/ai/data-sync/vector-spaces";

    private final Environment environment;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;
    private final ObjectProvider<AIEmbeddingService> embeddingServiceProvider;
    private final ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider;
    private final ObjectProvider<VectorManagementService> vectorManagementServiceProvider;
    private final ObjectProvider<AIAccessControlService> accessControlServiceProvider;
    private final ObjectProvider<AIEntityConfigurationLoader> entityConfigurationLoaderProvider;
    private final ObjectProvider<DataSyncService> dataSyncServiceProvider;
    private final ObjectProvider<DataSyncController> dataSyncControllerProvider;

    public RuntimeDataSyncDiagnosticsLogger(Environment environment,
                                            RequestMappingHandlerMapping requestMappingHandlerMapping,
                                            ObjectProvider<AIEmbeddingService> embeddingServiceProvider,
                                            ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider,
                                            ObjectProvider<VectorManagementService> vectorManagementServiceProvider,
                                            ObjectProvider<AIAccessControlService> accessControlServiceProvider,
                                            ObjectProvider<AIEntityConfigurationLoader> entityConfigurationLoaderProvider,
                                            ObjectProvider<DataSyncService> dataSyncServiceProvider,
                                            ObjectProvider<DataSyncController> dataSyncControllerProvider) {
        this.environment = environment;
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
        this.embeddingServiceProvider = embeddingServiceProvider;
        this.vectorDatabaseServiceProvider = vectorDatabaseServiceProvider;
        this.vectorManagementServiceProvider = vectorManagementServiceProvider;
        this.accessControlServiceProvider = accessControlServiceProvider;
        this.entityConfigurationLoaderProvider = entityConfigurationLoaderProvider;
        this.dataSyncServiceProvider = dataSyncServiceProvider;
        this.dataSyncControllerProvider = dataSyncControllerProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logDataSyncDiagnostics() {
        boolean dataSyncEnabled = environment.getProperty("ai.data-sync.enabled", Boolean.class, false);
        boolean embeddingsEnabled = environment.getProperty("ai.service.features.enable-embeddings", Boolean.class, false);
        boolean searchEnabled = environment.getProperty("ai.service.features.enable-search", Boolean.class, true);
        String vectorDbType = environment.getProperty("ai.vector-db.type", "");

        AIEntityConfigurationLoader entityConfigurationLoader = entityConfigurationLoaderProvider.getIfAvailable();
        Set<String> supportedEntityTypes = entityConfigurationLoader != null
            ? entityConfigurationLoader.getSupportedEntityTypes()
            : Set.of();

        boolean hasEmbeddingService = embeddingServiceProvider.getIfAvailable() != null;
        boolean hasVectorDatabaseService = vectorDatabaseServiceProvider.getIfAvailable() != null;
        boolean hasVectorManagementService = vectorManagementServiceProvider.getIfAvailable() != null;
        boolean hasAccessControlService = accessControlServiceProvider.getIfAvailable() != null;
        boolean hasDataSyncService = dataSyncServiceProvider.getIfAvailable() != null;
        boolean hasDataSyncController = dataSyncControllerProvider.getIfAvailable() != null;
        boolean hasVectorSpacesRoute = hasRoute(VECTOR_SPACES_PATH);

        Logger targetLogger = hasVectorSpacesRoute ? log : LoggerFactory.getLogger(getClass().getName() + ".missing");
        String message = hasVectorSpacesRoute
            ? "Runtime data sync diagnostics: enabled={}, embeddingsEnabled={}, searchEnabled={}, vectorDbType={}, hasEmbeddingService={}, hasVectorDatabaseService={}, hasVectorManagementService={}, hasAccessControlService={}, hasEntityConfigurationLoader={}, hasDataSyncService={}, hasDataSyncController={}, hasVectorSpacesRoute={}, supportedEntityTypes={}"
            : "Runtime data sync diagnostics: endpoint missing. enabled={}, embeddingsEnabled={}, searchEnabled={}, vectorDbType={}, hasEmbeddingService={}, hasVectorDatabaseService={}, hasVectorManagementService={}, hasAccessControlService={}, hasEntityConfigurationLoader={}, hasDataSyncService={}, hasDataSyncController={}, hasVectorSpacesRoute={}, supportedEntityTypes={}";

        if (hasVectorSpacesRoute) {
            targetLogger.info(
                message,
                dataSyncEnabled,
                embeddingsEnabled,
                searchEnabled,
                vectorDbType,
                hasEmbeddingService,
                hasVectorDatabaseService,
                hasVectorManagementService,
                hasAccessControlService,
                entityConfigurationLoader != null,
                hasDataSyncService,
                hasDataSyncController,
                hasVectorSpacesRoute,
                supportedEntityTypes
            );
        } else {
            targetLogger.warn(
                message,
                dataSyncEnabled,
                embeddingsEnabled,
                searchEnabled,
                vectorDbType,
                hasEmbeddingService,
                hasVectorDatabaseService,
                hasVectorManagementService,
                hasAccessControlService,
                entityConfigurationLoader != null,
                hasDataSyncService,
                hasDataSyncController,
                hasVectorSpacesRoute,
                supportedEntityTypes
            );
            targetLogger.warn("Runtime request mappings for data sync: {}", dataSyncRoutes());
        }
    }

    private boolean hasRoute(String path) {
        return requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
            .map(RequestMappingInfo::getPatternValues)
            .anyMatch(patterns -> patterns.contains(path));
    }

    private List<String> dataSyncRoutes() {
        return requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
            .map(RequestMappingInfo::getPatternValues)
            .flatMap(Set::stream)
            .filter(pattern -> pattern.contains("/api/ai/data-sync"))
            .sorted()
            .toList();
    }
}
