package ai.fabric.aspect;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AIProcess;
import ai.fabric.annotation.AISearchable;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessContractException;
import ai.fabric.indexing.api.AIProcessInvocation;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.AIProcessTarget;
import ai.fabric.indexing.api.AIProcessTargetResolver;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AIProcessAspectTest {

    @Test
    void usesTypedOperationRegardlessOfMethodNameWithoutRequiringTransaction()
        throws Throwable {
        TestFixture fixture = fixture();
        TestEntity entity = new TestEntity("p-1", "Laptop");
        Method method = LifecycleService.class.getMethod(
            "deleteNamedMethod",
            String.class
        );

        Object result = fixture.invoke(method, new Object[]{null}, entity);

        assertThat(result).isSameAs(entity);
        verify(fixture.dispatcher).upsert(
            entity,
            AIProcessOperation.CREATE,
            IndexingStrategy.AUTO
        );
        verify(fixture.dispatcher, never()).delete(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(IndexingStrategy.class)
        );
    }

    @Test
    void resolvesOptionalCollectionsAndArraysDeliberately() throws Throwable {
        TestFixture fixture = fixture();
        TestEntity first = new TestEntity("p-1", "One");
        TestEntity second = new TestEntity("p-2", "Two");

        fixture.invoke(
            LifecycleService.class.getMethod("optionalResult"),
            new Object[0],
            Optional.of(first)
        );
        fixture.invoke(
            LifecycleService.class.getMethod("collectionResult"),
            new Object[0],
            List.of(first, second)
        );
        fixture.invoke(
            LifecycleService.class.getMethod("arrayResult"),
            new Object[0],
            new TestEntity[]{first, second}
        );

        verify(fixture.dispatcher, times(5)).upsert(
            org.mockito.ArgumentMatchers.any(TestEntity.class),
            org.mockito.ArgumentMatchers.eq(AIProcessOperation.UPDATE),
            org.mockito.ArgumentMatchers.eq(IndexingStrategy.AUTO)
        );
    }

    @Test
    void customResolverSupportsWrapperNullArgumentAndVoidDelete() throws Throwable {
        CapturingWrapperResolver wrapperResolver = new CapturingWrapperResolver();
        TestFixture fixture = fixture(wrapperResolver, new DeleteIdResolver());
        TestEntity entity = new TestEntity("p-4", "Wrapped");
        Wrapper wrapper = new Wrapper(entity);

        fixture.invoke(
            LifecycleService.class.getMethod("wrapperResult", String.class),
            new Object[]{null},
            wrapper
        );
        fixture.invoke(
            LifecycleService.class.getMethod("voidDelete", String.class),
            new Object[]{"p-9"},
            null
        );

        assertThat(wrapperResolver.sawNullArgument).isTrue();
        verify(fixture.dispatcher).upsert(
            entity,
            AIProcessOperation.UPDATE,
            IndexingStrategy.SYNC
        );
        verify(fixture.dispatcher).delete(
            TestEntity.class,
            "p-9",
            IndexingStrategy.AUTO
        );
    }

    @Test
    void rejectsEntityTypeMismatchAndEmptyDefaultTarget() throws Throwable {
        TestFixture fixture = fixture();
        TestEntity entity = new TestEntity("p-1", "Laptop");

        assertThatThrownBy(() -> fixture.invoke(
            LifecycleService.class.getMethod("wrongEntityType"),
            new Object[0],
            entity
        ))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessageContaining("does not match");

        assertThatThrownBy(() -> fixture.invoke(
            LifecycleService.class.getMethod("emptyResult"),
            new Object[0],
            Optional.empty()
        ))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessageContaining("no result entity");
    }

    @Test
    void propagatesDomainExceptionAndNeverDispatches() throws Throwable {
        TestFixture fixture = fixture();
        IllegalStateException domainFailure = new IllegalStateException(
            "domain failure"
        );
        Method method = LifecycleService.class.getMethod("domainFailure");
        ProceedingJoinPoint joinPoint = fixture.joinPoint(
            method,
            new Object[0],
            null
        );
        doThrow(domainFailure).when(joinPoint).proceed();

        assertThatThrownBy(() -> fixture.aspect.process(
            joinPoint,
            method.getAnnotation(AIProcess.class)
        )).isSameAs(domainFailure);
        verify(fixture.dispatcher, never()).upsert(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void entityIndexingKillSwitchDoesNotFailOrDispatchDomainWork()
        throws Throwable {
        TestFixture fixture = fixture(
            new MockEnvironment().withProperty(
                "ai-entities.test-product.indexing.enabled",
                "false"
            )
        );
        TestEntity entity = new TestEntity("p-disabled", "Disabled");

        Object result = fixture.invoke(
            LifecycleService.class.getMethod("deleteNamedMethod", String.class),
            new Object[]{null},
            entity
        );
        Object empty = fixture.invoke(
            LifecycleService.class.getMethod("emptyResult"),
            new Object[0],
            Optional.empty()
        );

        assertThat(result).isSameAs(entity);
        assertThat(empty).isEqualTo(Optional.empty());
        verify(fixture.dispatcher, never()).upsert(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
        verify(fixture.dispatcher, never()).delete(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(IndexingStrategy.class)
        );
    }

    private TestFixture fixture(AIProcessTargetResolver... resolvers) {
        return fixture(new MockEnvironment(), resolvers);
    }

    private TestFixture fixture(
        MockEnvironment environment,
        AIProcessTargetResolver... resolvers
    ) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        for (int index = 0; index < resolvers.length; index++) {
            beanFactory.addBean("resolver-" + index, resolvers[index]);
        }
        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(
            environment
        );
        loader.loadConfiguration();
        AIEntityDescriptorRegistry registry = new AIEntityDescriptorRegistry(
            loader,
            List.of(),
            List.of(),
            beanFactory.getBeanProvider(PIIDetectionService.class),
            new ObjectMapper()
        );
        AIEntityIndexingGateway dispatcher = mock(
            AIEntityIndexingGateway.class
        );
        return new TestFixture(
            new LifecycleService(),
            dispatcher,
            new AIProcessAspect(dispatcher, registry, beanFactory)
        );
    }

    private static class TestFixture {
        private final LifecycleService service;
        private final AIEntityIndexingGateway dispatcher;
        private final AIProcessAspect aspect;

        private TestFixture(
            LifecycleService service,
            AIEntityIndexingGateway dispatcher,
            AIProcessAspect aspect
        ) {
            this.service = service;
            this.dispatcher = dispatcher;
            this.aspect = aspect;
        }

        private Object invoke(
            Method method,
            Object[] arguments,
            Object result
        ) throws Throwable {
            ProceedingJoinPoint joinPoint = joinPoint(method, arguments, result);
            return aspect.process(
                joinPoint,
                method.getAnnotation(AIProcess.class)
            );
        }

        private ProceedingJoinPoint joinPoint(
            Method method,
            Object[] arguments,
            Object result
        ) throws Throwable {
            ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
            MethodSignature signature = mock(MethodSignature.class);
            when(signature.getMethod()).thenReturn(method);
            when(joinPoint.getSignature()).thenReturn(signature);
            when(joinPoint.getTarget()).thenReturn(service);
            when(joinPoint.getArgs()).thenReturn(arguments);
            when(joinPoint.proceed()).thenReturn(result);
            return joinPoint;
        }
    }

    static class LifecycleService {

        @AIProcess(operation = AIProcessOperation.CREATE)
        public TestEntity deleteNamedMethod(String nullable) {
            throw new UnsupportedOperationException();
        }

        @AIProcess(operation = AIProcessOperation.UPDATE)
        public Optional<TestEntity> optionalResult() {
            throw new UnsupportedOperationException();
        }

        @AIProcess(operation = AIProcessOperation.UPDATE)
        public Collection<TestEntity> collectionResult() {
            throw new UnsupportedOperationException();
        }

        @AIProcess(operation = AIProcessOperation.UPDATE)
        public TestEntity[] arrayResult() {
            throw new UnsupportedOperationException();
        }

        @AIProcess(
            operation = AIProcessOperation.UPDATE,
            targetResolver = CapturingWrapperResolver.class,
            indexingStrategy = IndexingStrategy.SYNC
        )
        public Wrapper wrapperResult(String nullable) {
            throw new UnsupportedOperationException();
        }

        @AIProcess(
            operation = AIProcessOperation.DELETE,
            entityType = "test-product",
            targetResolver = DeleteIdResolver.class
        )
        public void voidDelete(String id) {
            throw new UnsupportedOperationException();
        }

        @AIProcess(
            operation = AIProcessOperation.UPDATE,
            entityType = "wrong"
        )
        public TestEntity wrongEntityType() {
            throw new UnsupportedOperationException();
        }

        @AIProcess(operation = AIProcessOperation.UPDATE)
        public Optional<TestEntity> emptyResult() {
            throw new UnsupportedOperationException();
        }

        @AIProcess(operation = AIProcessOperation.CREATE)
        public TestEntity domainFailure() {
            throw new UnsupportedOperationException();
        }
    }

    public static class CapturingWrapperResolver
        implements AIProcessTargetResolver {

        private boolean sawNullArgument;

        @Override
        public Collection<AIProcessTarget> resolve(
            AIProcessInvocation invocation
        ) {
            sawNullArgument = invocation.arguments().contains(null);
            Wrapper wrapper = (Wrapper) invocation.result();
            return List.of(AIProcessTarget.upsert(
                TestEntity.class,
                wrapper.entity()
            ));
        }
    }

    public static class DeleteIdResolver implements AIProcessTargetResolver {

        @Override
        public Collection<AIProcessTarget> resolve(
            AIProcessInvocation invocation
        ) {
            return List.of(AIProcessTarget.delete(
                TestEntity.class,
                String.valueOf(invocation.arguments().getFirst())
            ));
        }
    }

    record Wrapper(TestEntity entity) {
    }

    @AICapable(entityType = "test-product")
    static class TestEntity {
        @AIIdentity
        private final String id;

        @AISearchable(required = true)
        private final String title;

        TestEntity(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }
}
