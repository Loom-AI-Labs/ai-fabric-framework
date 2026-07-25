package ai.fabric.migration.service;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.NoMigrationRepository;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIEntityDescriptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.core.annotation.AnnotatedElementUtils;

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
    private final AIEntityDescriptorRegistry descriptorRegistry;
    private final ApplicationContext applicationContext;

    public EntityRepositoryRegistry(
        Repositories repositories,
        AIEntityDescriptorRegistry descriptorRegistry,
        ApplicationContext applicationContext
    ) {
        this.repositories = repositories;
        this.descriptorRegistry = descriptorRegistry;
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

            AICapable annotation = AnnotatedElementUtils.findMergedAnnotation(
                domainType,
                AICapable.class
            );
            if (annotation == null) {
                continue;
            }

            AIEntityDescriptor descriptor = descriptorRegistry.resolve(domainType);
            String entityType = descriptor.entityType();

            Class<? extends JpaRepository<?, ?>> repoClass =
                resolveRepositoryClass(descriptor, repository);
            JpaRepository<?, ?> repoBean = resolveRepositoryBean(repoClass, repository);
            EntityRegistration previous = registry.putIfAbsent(
                entityType,
                new EntityRegistration(entityType, domainType, repoBean)
            );
            if (previous != null && !previous.entityClass().equals(domainType)) {
                throw new IllegalStateException(
                    "Duplicate migration entityType '%s' for %s and %s"
                        .formatted(
                            entityType,
                            previous.entityClass().getName(),
                            domainType.getName()
                        )
                );
            }
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

    private Class<? extends JpaRepository<?, ?>> resolveRepositoryClass(
        AIEntityDescriptor descriptor,
        Object discoveredRepository
    ) {
        Class<? extends JpaRepository<?, ?>> candidate =
            descriptor.migrationRepository();
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
