package ai.fabric.indexing.descriptor;

import ai.fabric.annotation.AICapable;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Compiles explicit and JPA-backed entity descriptors during startup.
 */
public class AIEntityDescriptorInitializer implements SmartInitializingSingleton {

    private final AIEntityDescriptorRegistry registry;
    private final ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider;
    private boolean initialized;

    public AIEntityDescriptorInitializer(
        AIEntityDescriptorRegistry registry,
        ObjectProvider<EntityManagerFactory> entityManagerFactoryProvider
    ) {
        this.registry = registry;
        this.entityManagerFactoryProvider = entityManagerFactoryProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        initialize();
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        registry.compileContributors();
        EntityManagerFactory entityManagerFactory = entityManagerFactoryProvider.getIfAvailable();
        if (entityManagerFactory != null && entityManagerFactory.getMetamodel() != null) {
            for (EntityType<?> entityType : entityManagerFactory.getMetamodel().getEntities()) {
                Class<?> javaType = entityType.getJavaType();
                if (javaType != null && javaType.isAnnotationPresent(AICapable.class)) {
                    registry.resolve(javaType);
                }
            }
        }
        initialized = true;
    }
}
