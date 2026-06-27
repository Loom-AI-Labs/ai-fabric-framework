package ai.fabric.vector.contract;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.VectorDatabaseConfig;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.vector.milvus.MilvusVectorDatabaseService;
import ai.fabric.vector.qdrant.QdrantVectorDatabaseService;
import ai.fabric.vector.weaviate.WeaviateVectorDatabaseService;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static ai.fabric.vector.contract.VectorDatabaseServiceContractAssertions.STANDARD_EFFICIENT_COUNT;
import static ai.fabric.vector.contract.VectorDatabaseServiceContractAssertions.STANDARD_NON_EFFICIENT_COUNT;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class VectorDatabaseServiceContainerIT {

    private static final VectorDatabaseServiceContractAssertions CONTRACT =
        new VectorDatabaseServiceContractAssertions();

    private static final int QDRANT_REST_PORT = 6333;
    private static final int QDRANT_GRPC_PORT = 6334;
    private static final int WEAVIATE_PORT = 8080;
    private static final int MILVUS_PORT = 19530;
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    @TestFactory
    Stream<DynamicNode> containerProviderContracts() {
        return providerSpecs().map(spec -> dynamicContainer(spec.name(), Stream.of(
            dynamicTest("passes VectorDatabaseService lifecycle contract against real container", () -> {
                try (ProviderFixture fixture = spec.fixture().get()) {
                    CONTRACT.assertFullLifecycleContract(fixture.service(), spec.capabilities());
                }
            }),
            dynamicTest("isolates records across configured provider scopes", () -> {
                try (ProviderScopeFixture fixture = spec.scopeFixture().get()) {
                    CONTRACT.assertProviderScopeIsolation(fixture.left(), fixture.right());
                }
            })
        )));
    }

    private Stream<ContainerProviderSpec> providerSpecs() {
        return Stream.of(
            new ContainerProviderSpec("qdrant-rest", this::qdrantRestFixture, this::qdrantRestScopeFixture, STANDARD_EFFICIENT_COUNT),
            new ContainerProviderSpec("qdrant-grpc", this::qdrantGrpcFixture, this::qdrantGrpcScopeFixture, STANDARD_EFFICIENT_COUNT),
            new ContainerProviderSpec("weaviate", this::weaviateFixture, this::weaviateScopeFixture, STANDARD_EFFICIENT_COUNT),
            new ContainerProviderSpec("milvus", this::milvusFixture, this::milvusScopeFixture, STANDARD_NON_EFFICIENT_COUNT)
        );
    }

    private ProviderFixture qdrantRestFixture() {
        return qdrantFixture(false, "qdrant_rest");
    }

    private ProviderFixture qdrantGrpcFixture() {
        return qdrantFixture(true, "qdrant_grpc");
    }

    private ProviderScopeFixture qdrantRestScopeFixture() {
        return qdrantScopeFixture(false, "qdrant_rest_scope");
    }

    private ProviderScopeFixture qdrantGrpcScopeFixture() {
        return qdrantScopeFixture(true, "qdrant_grpc_scope");
    }

    private ProviderFixture qdrantFixture(boolean preferGrpc, String scope) {
        GenericContainer<?> container = startQdrantContainer();
        AIProviderConfig config = qdrantConfig(container, preferGrpc, scope);

        return new ProviderFixture(new QdrantVectorDatabaseService(config, vectorConfig()), container);
    }

    private ProviderScopeFixture qdrantScopeFixture(boolean preferGrpc, String scope) {
        GenericContainer<?> container = startQdrantContainer();
        VectorDatabaseService left = new QdrantVectorDatabaseService(
            qdrantConfig(container, preferGrpc, scope + "_left"), vectorConfig());
        VectorDatabaseService right = new QdrantVectorDatabaseService(
            qdrantConfig(container, preferGrpc, scope + "_right"), vectorConfig());
        return new ProviderScopeFixture(left, right, container);
    }

    private ProviderFixture weaviateFixture() {
        GenericContainer<?> container = startWeaviateContainer();
        AIProviderConfig config = weaviateConfig(container, "weaviate");

        return new ProviderFixture(new WeaviateVectorDatabaseService(config, vectorConfig()), container);
    }

    private ProviderScopeFixture weaviateScopeFixture() {
        GenericContainer<?> container = startWeaviateContainer();
        VectorDatabaseService left = new WeaviateVectorDatabaseService(
            weaviateConfig(container, "weaviate_left"), vectorConfig());
        VectorDatabaseService right = new WeaviateVectorDatabaseService(
            weaviateConfig(container, "weaviate_right"), vectorConfig());
        return new ProviderScopeFixture(left, right, container);
    }

    private ProviderFixture milvusFixture() {
        GenericContainer<?> container = startMilvusContainer();
        AIProviderConfig config = milvusConfig(container, "milvus");

        return new ProviderFixture(new MilvusVectorDatabaseService(config), container);
    }

    private ProviderScopeFixture milvusScopeFixture() {
        GenericContainer<?> container = startMilvusContainer();
        VectorDatabaseService left = new MilvusVectorDatabaseService(milvusConfig(container, "milvus_left"));
        VectorDatabaseService right = new MilvusVectorDatabaseService(milvusConfig(container, "milvus_right"));
        return new ProviderScopeFixture(left, right, container);
    }

    private GenericContainer<?> startQdrantContainer() {
        GenericContainer<?> container = new GenericContainer<>(
            DockerImageName.parse(image("qdrant", "qdrant/qdrant:v1.16.1"))
        )
            .withExposedPorts(QDRANT_REST_PORT, QDRANT_GRPC_PORT)
            .waitingFor(Wait.forHttp("/readyz")
                .forPort(QDRANT_REST_PORT)
                .forStatusCode(200)
                .withStartupTimeout(STARTUP_TIMEOUT));
        container.start();
        return container;
    }

    private AIProviderConfig qdrantConfig(GenericContainer<?> container, boolean preferGrpc, String scope) {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.QdrantConfig qdrant = config.getQdrant();
        qdrant.setEnabled(true);
        qdrant.setHost(container.getHost());
        qdrant.setPort(container.getMappedPort(QDRANT_REST_PORT));
        qdrant.setGrpcPort(container.getMappedPort(QDRANT_GRPC_PORT));
        qdrant.setPreferGrpc(preferGrpc);
        qdrant.setCollectionPrefix(scopePrefix(scope));
        return config;
    }

    private GenericContainer<?> startWeaviateContainer() {
        GenericContainer<?> container = new GenericContainer<>(
            DockerImageName.parse(image("weaviate", "semitechnologies/weaviate:1.23.0"))
        )
            .withExposedPorts(WEAVIATE_PORT)
            .withEnv("AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED", "true")
            .withEnv("PERSISTENCE_DATA_PATH", "/var/lib/weaviate")
            .withEnv("DEFAULT_VECTORIZER_MODULE", "none")
            .withEnv("CLUSTER_HOSTNAME", "node1")
            .waitingFor(Wait.forHttp("/v1/.well-known/ready")
                .forPort(WEAVIATE_PORT)
                .forStatusCode(200)
                .withStartupTimeout(STARTUP_TIMEOUT));
        container.start();
        return container;
    }

    private AIProviderConfig weaviateConfig(GenericContainer<?> container, String scope) {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.WeaviateConfig weaviate = config.getWeaviate();
        weaviate.setEnabled(true);
        weaviate.setScheme("http");
        weaviate.setHost(container.getHost());
        weaviate.setPort(container.getMappedPort(WEAVIATE_PORT));
        weaviate.setClassPrefix(scopeClassPrefix(scope));
        return config;
    }

    private GenericContainer<?> startMilvusContainer() {
        GenericContainer<?> container = new GenericContainer<>(
            DockerImageName.parse(image("milvus", "milvusdb/milvus:v2.4.0"))
        )
            .withExposedPorts(MILVUS_PORT)
            .withCommand("milvus", "run", "standalone")
            .withEnv("COMMON_STORAGETYPE", "local")
            .withEnv("ETCD_USE_EMBED", "true")
            .withEnv("MINIO_ADDRESS", "localhost")
            .withStartupTimeout(STARTUP_TIMEOUT)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP_TIMEOUT));
        container.start();
        return container;
    }

    private AIProviderConfig milvusConfig(GenericContainer<?> container, String scope) {
        AIProviderConfig config = new AIProviderConfig();
        AIProviderConfig.MilvusConfig milvus = config.getMilvus();
        milvus.setEnabled(true);
        milvus.setHost(container.getHost());
        milvus.setPort(container.getMappedPort(MILVUS_PORT));
        milvus.setDatabaseName("default");
        milvus.setSecure(false);
        milvus.setCollectionPrefix(scopePrefix(scope));
        milvus.setFlushOnWrite(true);
        return config;
    }

    private static VectorDatabaseConfig vectorConfig() {
        VectorDatabaseConfig config = new VectorDatabaseConfig();
        config.getOperations().setAwaitClearConsistency(true);
        config.getOperations().setAwaitClearTimeoutMs(30_000L);
        return config;
    }

    private static String image(String provider, String defaultImage) {
        return System.getProperty("testcontainers." + provider + ".image", defaultImage);
    }

    private static String scopePrefix(String provider) {
        return "contract_" + provider + "_" + suffix() + "_";
    }

    private static String scopeClassPrefix(String provider) {
        return "Contract" + provider + suffix();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private record ContainerProviderSpec(
        String name,
        Supplier<ProviderFixture> fixture,
        Supplier<ProviderScopeFixture> scopeFixture,
        VectorDatabaseServiceContractAssertions.CapabilityExpectations capabilities
    ) {
    }

    private record ProviderFixture(VectorDatabaseService service, GenericContainer<?> container) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                service.clearVectors();
            } catch (Exception ignored) {
                // Keep teardown best-effort so the original assertion failure stays visible.
            }
            if (service instanceof AutoCloseable closeable) {
                closeable.close();
            }
            container.stop();
        }
    }

    private record ProviderScopeFixture(
        VectorDatabaseService left,
        VectorDatabaseService right,
        GenericContainer<?> container
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                left.clearVectors();
            } catch (Exception ignored) {
                // Keep teardown best-effort so the original assertion failure stays visible.
            }
            try {
                right.clearVectors();
            } catch (Exception ignored) {
                // Keep teardown best-effort so the original assertion failure stays visible.
            }
            if (left instanceof AutoCloseable closeable) {
                closeable.close();
            }
            if (right instanceof AutoCloseable closeable) {
                closeable.close();
            }
            container.stop();
        }
    }
}
