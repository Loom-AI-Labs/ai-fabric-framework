package ai.fabric.it.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.containers.GenericContainer;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestcontainersSupportActivationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(VectorDatabaseContainerAutoConfiguration.class);

    @AfterEach
    void clearContainerState() throws Exception {
        earlyStartedContainers().clear();
        activeContainers().clear();
    }

    @Test
    void springFactoriesRegistersInitializer() throws Exception {
        Properties factories = PropertiesLoaderUtils.loadProperties(new ClassPathResource("META-INF/spring.factories"));

        assertTrue(factories.getProperty("org.springframework.context.ApplicationContextInitializer")
            .contains(TestcontainersInitializer.class.getName()));
    }

    @Test
    void autoConfigurationDoesNotStartContainersWhenDisabled() {
        contextRunner
            .withPropertyValues(
                "testcontainers.enabled=false",
                "ai.vector-db.type=milvus")
            .run(context -> assertFalse(context.containsBean("milvusContainer")));
    }

    @Test
    void autoConfigurationDoesNotStartContainersForLucene() {
        contextRunner
            .withPropertyValues(
                "testcontainers.enabled=true",
                "ai.vector-db.type=lucene")
            .run(context -> assertTrue(context.getBeansOfType(GenericContainer.class).isEmpty()));
    }

    @Test
    void initializerLeavesTestcontainersDisabledForLuceneEvenWhenProfileActive() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().setActiveProfiles("testcontainers");
        TestPropertyValues.of("ai.vector-db.type=lucene").applyTo(context);

        new TestcontainersInitializer().initialize(context);

        assertNull(context.getEnvironment().getProperty("testcontainers.enabled"));
    }

    @Test
    void initializerDoesNotAutoEnableFutureFixtureTypesWhenProfileActive() {
        GenericApplicationContext chromaContext = new GenericApplicationContext();
        chromaContext.getEnvironment().setActiveProfiles("testcontainers");
        TestPropertyValues.of("ai.vector-db.type=chroma").applyTo(chromaContext);

        GenericApplicationContext pgvectorContext = new GenericApplicationContext();
        pgvectorContext.getEnvironment().setActiveProfiles("testcontainers");
        TestPropertyValues.of("ai.vector-db.type=pgvector").applyTo(pgvectorContext);

        TestcontainersInitializer initializer = new TestcontainersInitializer();
        initializer.initialize(chromaContext);
        initializer.initialize(pgvectorContext);

        assertNull(chromaContext.getEnvironment().getProperty("testcontainers.enabled"));
        assertNull(pgvectorContext.getEnvironment().getProperty("testcontainers.enabled"));
    }

    @Test
    void initializerDoesNotAutoEnableFutureFixtureTypesWhenExplicitlySpecified() {
        String previous = System.getProperty("ai.vector-db.type");
        System.setProperty("ai.vector-db.type", "chroma");
        try {
            GenericApplicationContext context = new GenericApplicationContext();
            TestPropertyValues.of("ai.vector-db.type=chroma").applyTo(context);

            new TestcontainersInitializer().initialize(context);

            assertNull(context.getEnvironment().getProperty("testcontainers.enabled"));
        } finally {
            if (previous == null) {
                System.clearProperty("ai.vector-db.type");
            } else {
                System.setProperty("ai.vector-db.type", previous);
            }
        }
    }

    @Test
    void initializerFailsFastWithoutPartiallyEnablingTestcontainersWhenDockerUnavailable() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().setActiveProfiles("testcontainers");
        TestPropertyValues.of("ai.vector-db.type=milvus").applyTo(context);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new DockerUnavailableInitializer().initialize(context));

        assertTrue(failure.getMessage().contains("Failed to start Milvus container early"));
        assertNull(context.getEnvironment().getProperty("testcontainers.enabled"));
    }

    @Test
    void autoConfigurationReusesEarlyStartedQdrantContainer() throws Exception {
        GenericContainer<?> qdrant = runningContainer("localhost", 16333);
        earlyStartedContainers().put("qdrant", qdrant);

        VectorDatabaseContainerAutoConfiguration configuration = new VectorDatabaseContainerAutoConfiguration();

        GenericContainer<?> returned = configuration.qdrantContainer(new StandardEnvironment());

        assertSame(qdrant, returned);
        assertSame(qdrant, activeContainers().get("qdrant"));
        verify(qdrant, never()).start();
    }

    @Test
    void autoConfigurationReusesEarlyStartedWeaviateContainer() throws Exception {
        GenericContainer<?> weaviate = runningContainer("localhost", 18080);
        earlyStartedContainers().put("weaviate", weaviate);

        VectorDatabaseContainerAutoConfiguration configuration = new VectorDatabaseContainerAutoConfiguration();

        GenericContainer<?> returned = configuration.weaviateContainer(new StandardEnvironment());

        assertSame(weaviate, returned);
        assertSame(weaviate, activeContainers().get("weaviate"));
        verify(weaviate, never()).start();
    }

    @Test
    void cleanupRemovesMatchingEarlyStartedContainerReference() throws Exception {
        GenericContainer<?> qdrant = runningContainer("localhost", 16333);
        earlyStartedContainers().put("qdrant", qdrant);

        VectorDatabaseContainerAutoConfiguration configuration = new VectorDatabaseContainerAutoConfiguration();
        configuration.qdrantContainer(new StandardEnvironment());

        configuration.cleanup();

        assertFalse(activeContainers().containsKey("qdrant"));
        assertFalse(earlyStartedContainers().containsKey("qdrant"));
        verify(qdrant, times(1)).stop();
    }

    private static class DockerUnavailableInitializer extends TestcontainersInitializer {
        @Override
        protected void verifyDockerAvailable() {
            throw new IllegalStateException("Docker is unavailable for test");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, GenericContainer<?>> earlyStartedContainers() throws Exception {
        Field field = TestcontainersInitializer.class.getDeclaredField("earlyStartedContainers");
        field.setAccessible(true);
        return (Map<String, GenericContainer<?>>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, GenericContainer<?>> activeContainers() throws Exception {
        Field field = VectorDatabaseContainerAutoConfiguration.class.getDeclaredField("activeContainers");
        field.setAccessible(true);
        return (Map<String, GenericContainer<?>>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static GenericContainer<?> runningContainer(String host, int mappedPort) {
        GenericContainer<?> container = mock(GenericContainer.class);
        when(container.isRunning()).thenReturn(true);
        when(container.getHost()).thenReturn(host);
        when(container.getMappedPort(org.mockito.ArgumentMatchers.anyInt())).thenReturn(mappedPort);
        return container;
    }
}
