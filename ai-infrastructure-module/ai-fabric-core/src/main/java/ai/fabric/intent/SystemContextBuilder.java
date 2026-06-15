package ai.fabric.intent;

import ai.fabric.intent.action.ActionInfo;
import ai.fabric.intent.action.AvailableActionsRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.spi.BehaviorContextProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the aggregated context object consumed by the prompt builder.
 */
@Slf4j
@Service
public class SystemContextBuilder {

    private final AvailableActionsRegistry availableActionsRegistry;
    @Nullable
    private final KnowledgeBaseOverviewService knowledgeBaseOverviewService;
    private final Optional<BehaviorContextProvider> behaviorContextProvider;
    private final Clock clock;
    private final Optional<BeanFactory> beanFactory;
    
    // Cache for entity types to avoid repeated reflection calls
    private volatile Set<String> cachedEntityTypes = null;
    private volatile boolean entityTypesCacheInitialized = false;

    @Autowired
    public SystemContextBuilder(AvailableActionsRegistry availableActionsRegistry,
                                ObjectProvider<KnowledgeBaseOverviewService> knowledgeBaseOverviewServiceProvider,
                                ObjectProvider<BehaviorContextProvider> behaviorContextProvider,
                                ObjectProvider<Clock> clockProvider,
                                ObjectProvider<BeanFactory> beanFactoryProvider) {
        this.availableActionsRegistry = availableActionsRegistry;
        this.knowledgeBaseOverviewService = knowledgeBaseOverviewServiceProvider.getIfAvailable();
        this.behaviorContextProvider = Optional.ofNullable(behaviorContextProvider.getIfAvailable());
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        this.beanFactory = Optional.ofNullable(beanFactoryProvider.getIfAvailable());
    }


    /**
     * Builds the system context for intent extraction, including available actions,
     * knowledge base overview, behavior context, and entity types.
     * 
     * <p>Entity types are automatically extracted from the relationship-query module
     * if present, enabling more accurate entity type extraction in the LLM prompt.</p>
     * 
     * @param orchestrationContext The orchestration context containing user info
     * @return Fully populated system context for prompt building
     */
    public SystemContext buildContext(OrchestrationContext orchestrationContext) {
        List<ActionInfo> actions = availableActionsRegistry.getAllAvailableActions();
        KnowledgeBaseOverview overview = knowledgeBaseOverviewService != null ? knowledgeBaseOverviewService.getOverview() : null;

        SystemContext.SystemContextBuilder builder = SystemContext.builder()
            .availableActions(actions)
            .knowledgeBaseOverview(overview)
            .userId(orchestrationContext.getUserId())
            .sessionId(orchestrationContext.getSessionId())
            .authenticated(orchestrationContext.isAuthenticated())
            .locale(orchestrationContext.getLocale())
            .metadata(orchestrationContext.getMetadata())
            .timestamp(LocalDateTime.now(clock))
            .availableEntityTypes(getAvailableEntityTypes());

        behaviorContextProvider.ifPresent(provider ->
            provider.getBehaviorContext(orchestrationContext)
                .ifPresent(builder::behaviorContext)
        );

        return builder.build();
    }

    /**
     * Extracts available entity types from EntityRelationshipMapper.
     * This is mandatory when the relationship-query module is present.
     * Uses reflection to avoid hard dependency on relationship-query module.
     * 
     * <p>Results are cached after first call to avoid repeated reflection overhead.</p>
     * 
     * @return Set of registered entity types, or empty set if relationship-query module is not present
     */
    private Set<String> getAvailableEntityTypes() {
        // Return cached value if available
        if (entityTypesCacheInitialized) {
            return cachedEntityTypes != null ? cachedEntityTypes : Set.of();
        }
        
        // Double-checked locking for thread-safe lazy initialization
        synchronized (this) {
            if (entityTypesCacheInitialized) {
                return cachedEntityTypes != null ? cachedEntityTypes : Set.of();
            }
            
            Set<String> entityTypes = extractEntityTypesViaReflection();
            cachedEntityTypes = entityTypes;
            entityTypesCacheInitialized = true;
            
            if (log.isDebugEnabled()) {
                if (entityTypes.isEmpty()) {
                    log.debug("No entity types extracted - relationship-query module not present or not configured");
                } else {
                    log.debug("Cached {} entity types for LLM prompt: {}", entityTypes.size(), entityTypes);
                }
            }
            
            return entityTypes;
        }
    }
    
    /**
     * Performs the actual reflection-based entity type extraction.
     * Separated from getAvailableEntityTypes() to keep caching logic clean.
     */
    private Set<String> extractEntityTypesViaReflection() {
        if (beanFactory.isEmpty()) {
            return Set.of();
        }

        try {
            // Try to get EntityRelationshipMapper bean by class name (relationship-query module)
            Class<?> mapperClass = Class.forName("ai.fabric.relationship.service.EntityRelationshipMapper");
            Object mapper = beanFactory.get().getBean(mapperClass);
            
            // Use reflection to call getAllEntityMappings() method
            java.lang.reflect.Method method = mapperClass.getMethod("getAllEntityMappings");
            @SuppressWarnings("unchecked")
            Map<String, ?> mappings = (Map<String, ?>) method.invoke(mapper);
            
            // Must return entity types if mapper exists - empty set if no entities registered
            return mappings != null ? mappings.keySet() : Set.of();
        } catch (ClassNotFoundException ex) {
            // EntityRelationshipMapper class not found - relationship-query module not present
            log.debug("EntityRelationshipMapper not found - relationship-query module not present");
            return Set.of();
        } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException ex) {
            // Bean not registered - relationship-query module not configured
            log.debug("EntityRelationshipMapper bean not registered - relationship-query module not configured");
            return Set.of();
        } catch (Exception ex) {
            // Log unexpected reflection errors to aid debugging
            log.warn("Failed to extract entity types from EntityRelationshipMapper: {}. " +
                "Entity type hints will not be available in LLM prompt.", ex.getMessage());
            return Set.of();
        }
    }

    @Deprecated(forRemoval = true)
    public SystemContext buildContext(String userId) {
        return buildContext(OrchestrationContext.forUser(userId));
    }
}
