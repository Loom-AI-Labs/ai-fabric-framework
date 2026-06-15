package ai.fabric.migration.service;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.NoMigrationRepository;
import ai.fabric.config.AIEntityConfigurationLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers @AICapable entities and wires them to their JPA repositories.
 */
@Slf4j
public class EntityRepositoryRegistry {

    private final Map<String, EntityRegistration> registry = new ConcurrentHashMap<>();
    private final Repositories repositories;
    private final AIEntityConfigurationLoader configLoader;
    private final ApplicationContext applicationContext;

    public EntityRepositoryRegistry(
        Repositories repositories,
        AIEntityConfigurationLoader configLoader,
        ApplicationContext applicationContext
    ) {
        this.repositories = repositories;
        this.configLoader = configLoader;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void discoverEntities() {
        for (Class<?> domainType : repositories) {
            Optional<Object> repositoryOptional = repositories.getRepositoryFor(domainType);
            if (repositoryOptional.isEmpty()) {
                continue;
            }
            Object repository = repositoryOptional.get();

            AICapable annotation = domainType.getAnnotation(AICapable.class);
            if (annotation == null) {
                continue;
            }

            String entityType = annotation.entityType();
            if (!configLoader.hasEntityConfig(entityType)) {
                log.debug("Skipping entity {} because no ai-entity-config entry found", entityType);
                continue;
            }

            Class<? extends JpaRepository<?, ?>> repoClass = resolveRepositoryClass(annotation, repository);
            JpaRepository<?, ?> repoBean = resolveRepositoryBean(repoClass, repository);
            registry.put(entityType, new EntityRegistration(entityType, domainType, repoBean));
            log.info("Registered migration repository {} for entity type {}", repoClass.getSimpleName(), entityType);
        }

        if (registry.isEmpty()) {
            log.warn("No @AICapable entities with repositories were registered for migration");
        }
    }

    public EntityRegistration getRegistration(String entityType) {
        return Optional.ofNullable(registry.get(entityType))
            .orElseThrow(() -> new IllegalArgumentException("No repository registration for entity type: " + entityType));
    }

    private Class<? extends JpaRepository<?, ?>> resolveRepositoryClass(AICapable annotation, Object discoveredRepository) {
        Class<? extends JpaRepository<?, ?>> candidate = annotation.migrationRepository();
        if (candidate != null
            && !JpaRepository.class.equals(candidate)
            && !NoMigrationRepository.class.equals(candidate)) {
            return candidate;
        }
        // Fallback to the discovered repository bean type when explicit binding is not provided.
        @SuppressWarnings("unchecked")
        Class<? extends JpaRepository<?, ?>> repositoryClass =
            (Class<? extends JpaRepository<?, ?>>) discoveredRepository.getClass();
        return repositoryClass;
    }

    private JpaRepository<?, ?> resolveRepositoryBean(Class<? extends JpaRepository<?, ?>> repoClass, Object discoveredRepository) {
        if (repoClass != null
            && !JpaRepository.class.equals(repoClass)
            && !NoMigrationRepository.class.equals(repoClass)
            && !repoClass.isInstance(discoveredRepository)) {
            return applicationContext.getBean(repoClass);
        }
        return (JpaRepository<?, ?>) discoveredRepository;
    }
}
