package ai.fabric.aspect;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AIProcess;
import ai.fabric.annotation.AISearchable;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.api.AIProcessContractException;
import ai.fabric.indexing.api.AIProcessInvocation;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AIProcessTarget;
import ai.fabric.indexing.api.AIProcessTargetResolver;
import ai.fabric.indexing.descriptor.AIEntityDescriptorInitializer;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIEntityDescriptor;
import ai.fabric.indexing.projection.AIConfiguredEntityProjectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIEntityContractValidatorTest {

    @Test
    void acceptsDirectOptionalCollectionAndArrayEntityResults() throws Exception {
        Fixture fixture = fixture();

        assertThatCode(() -> {
            fixture.validator.validateMethod(
                Service.class,
                Service.class.getMethod("direct")
            );
            fixture.validator.validateMethod(
                Service.class,
                Service.class.getMethod("optional")
            );
            fixture.validator.validateMethod(
                Service.class,
                Service.class.getMethod("many")
            );
            fixture.validator.validateMethod(
                Service.class,
                Service.class.getMethod("array")
            );
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsVoidAndWrapperResultsWithoutResolver() throws Exception {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.validator.validateMethod(
            Service.class,
            Service.class.getMethod("invalidVoid")
        ))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessageContaining("targetResolver");

        assertThatThrownBy(() -> fixture.validator.validateMethod(
            Service.class,
            Service.class.getMethod("invalidWrapper")
        ))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessageContaining("targetResolver");
    }

    @Test
    void acceptsApplicationOwnedResolverOnlyWhenExactlyOneBeanExists()
        throws Exception {
        Fixture fixture = fixture();
        fixture.beans.addBean("resolver", new Resolver());

        assertThatCode(() -> fixture.validator.validateMethod(
            Service.class,
            Service.class.getMethod("resolvedVoid")
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsEntityTypeAssertionMismatchAtStartup() throws Exception {
        Fixture fixture = fixture();
        Method method = Service.class.getMethod("wrongAssertion");

        assertThatThrownBy(() -> fixture.validator.validateMethod(
            Service.class,
            method
        ))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessageContaining("different")
            .hasMessageContaining("entity");
    }

    private Fixture fixture() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        AIEntityDescriptorRegistry registry = mock(AIEntityDescriptorRegistry.class);
        AIEntityDescriptor descriptor = mock(AIEntityDescriptor.class);
        when(descriptor.entityType()).thenReturn("entity");
        when(registry.resolve(Entity.class)).thenReturn(descriptor);
        AIEntityDescriptorInitializer initializer =
            mock(AIEntityDescriptorInitializer.class);
        AIEntityConfigurationLoader configurationLoader =
            mock(AIEntityConfigurationLoader.class);
        when(configurationLoader.getEntityConfigs()).thenReturn(Map.of());
        return new Fixture(
            beans,
            new AIEntityContractValidator(
                beans,
                registry,
                initializer,
                configurationLoader,
                mock(AIConfiguredEntityProjectionService.class)
            )
        );
    }

    private record Fixture(
        StaticListableBeanFactory beans,
        AIEntityContractValidator validator
    ) {
    }

    static class Service {

        @AIProcess(operation = AIProcessOperation.CREATE)
        public Entity direct() {
            return null;
        }

        @AIProcess(operation = AIProcessOperation.UPDATE)
        public Optional<Entity> optional() {
            return Optional.empty();
        }

        @AIProcess(operation = AIProcessOperation.UPDATE)
        public List<Entity> many() {
            return List.of();
        }

        @AIProcess(operation = AIProcessOperation.UPDATE)
        public Entity[] array() {
            return new Entity[0];
        }

        @AIProcess(operation = AIProcessOperation.DELETE)
        public void invalidVoid() {
        }

        @AIProcess(operation = AIProcessOperation.UPDATE)
        public Wrapper invalidWrapper() {
            return null;
        }

        @AIProcess(
            operation = AIProcessOperation.DELETE,
            targetResolver = Resolver.class
        )
        public void resolvedVoid() {
        }

        @AIProcess(
            operation = AIProcessOperation.UPDATE,
            entityType = "different"
        )
        public Entity wrongAssertion() {
            return null;
        }
    }

    @AICapable(entityType = "entity")
    static class Entity {
        @AIIdentity
        String id;

        @AISearchable
        String content;
    }

    record Wrapper(Entity entity) {
    }

    static class Resolver implements AIProcessTargetResolver {
        @Override
        public Collection<AIProcessTarget> resolve(AIProcessInvocation invocation) {
            return List.of();
        }
    }
}
