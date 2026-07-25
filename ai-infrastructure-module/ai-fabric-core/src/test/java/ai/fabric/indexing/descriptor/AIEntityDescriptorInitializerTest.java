package ai.fabric.indexing.descriptor;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.api.AIEntityDescriptorContributor;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIEntityDescriptorInitializerTest {

    @Test
    void discoversJpaAndExplicitNonJpaEntitiesWithoutInstantiatingThem() {
        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(
            new MockEnvironment()
        );
        loader.loadConfiguration();
        AIEntityDescriptorContributor contributor = () -> Set.of(
            ContributedEntity.class
        );
        AIEntityDescriptorRegistry registry = new AIEntityDescriptorRegistry(
            loader,
            List.of(),
            List.of(contributor),
            provider(PIIDetectionService.class, null),
            new ObjectMapper()
        );

        EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);
        Metamodel metamodel = mock(Metamodel.class);
        EntityType<JpaDiscoveredEntity> jpaEntity = mock(EntityType.class);
        EntityType<UnrelatedEntity> unrelatedEntity = mock(EntityType.class);
        when(entityManagerFactory.getMetamodel()).thenReturn(metamodel);
        when(metamodel.getEntities()).thenReturn(Set.of(jpaEntity, unrelatedEntity));
        when(jpaEntity.getJavaType()).thenReturn(JpaDiscoveredEntity.class);
        when(unrelatedEntity.getJavaType()).thenReturn(UnrelatedEntity.class);

        AIEntityDescriptorInitializer initializer = new AIEntityDescriptorInitializer(
            registry,
            provider(EntityManagerFactory.class, entityManagerFactory)
        );
        initializer.initialize();
        initializer.initialize();

        assertThat(registry.hasEntityType("jpa-discovered")).isTrue();
        assertThat(registry.hasEntityType("explicit-non-jpa")).isTrue();
        assertThat(registry.descriptors()).hasSize(2);
    }

    private <T> ObjectProvider<T> provider(Class<T> type, T value) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (value != null) {
            factory.addBean("value", value);
        }
        return factory.getBeanProvider(type);
    }

    @AICapable(entityType = "jpa-discovered")
    static class JpaDiscoveredEntity {
        @AIIdentity
        String id = "jpa-1";

        @AISearchable
        String text = "JPA";
    }

    @AICapable(entityType = "explicit-non-jpa")
    static class ContributedEntity {
        @AIIdentity
        String id = "external-1";

        @AISearchable
        String text = "External";
    }

    static class UnrelatedEntity {
    }
}
