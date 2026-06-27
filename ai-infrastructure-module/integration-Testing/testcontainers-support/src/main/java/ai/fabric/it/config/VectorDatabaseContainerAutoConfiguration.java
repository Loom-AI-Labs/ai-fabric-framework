package ai.fabric.it.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-configuration for vector database Testcontainers.
 *
 * <p>Automatically starts the appropriate container based on the
 * {@code ai.vector-db.type} property and injects connection properties
 * into the Spring environment.</p>
 *
 * <p><strong>Activation:</strong> This configuration only activates when:
 * <ul>
 *   <li>{@code testcontainers.enabled=true} (set automatically by {@link TestcontainersInitializer}
 *       for module-backed container types, or set explicitly for generic future-provider
 *       fixtures)</li>
 *   <li>{@code ai.vector-db.type} is set to a container-supported type</li>
 * </ul>
 * </p>
 *
 * <p><strong>Default Behavior:</strong> Unit tests default to Lucene (fast, no containers).
 * Testcontainers only activates when explicitly requested via Maven parameters.</p>
 *
 * <p><strong>Usage Examples:</strong></p>
 * <pre>
 * // Unit tests - uses Lucene by default (fast, no containers)
 * mvn test
 *
 * // Use Testcontainers with Milvus
 * mvn test -Dspring.profiles.active=testcontainers -Dai.vector-db.type=milvus
 *
 * // Use Testcontainers with Qdrant
 * mvn test -Dspring.profiles.active=testcontainers -Dai.vector-db.type=qdrant
 *
 * // Use Lucene explicitly (no containers, even with testcontainers profile)
 * mvn test -Dspring.profiles.active=testcontainers -Dai.vector-db.type=lucene
 * </pre>
 *
 * <p><strong>Module-backed Container Types:</strong> milvus, qdrant, weaviate</p>
 * <p><strong>Generic Future-provider Fixtures:</strong> chroma, pgvector. These container
 * helpers do not imply shipped AI Fabric Chroma or pgvector vector provider modules and are
 * not auto-enabled by {@link TestcontainersInitializer}.</p>
 *
 * <p><strong>Thread Safety:</strong> This configuration is thread-safe and supports
 * parallel test execution. Each provider type maintains its own container instance.</p>
 *
 * <p><strong>Container Lifecycle:</strong> Containers are automatically started when
 * the test context initializes and stopped when the context is destroyed.</p>
 *
 * @author AI Infrastructure Team
 * @version 1.1.0
 * @see TestcontainersInitializer
 */
@TestConfiguration
@ConditionalOnProperty(name = "testcontainers.enabled", havingValue = "true", matchIfMissing = false)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VectorDatabaseContainerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VectorDatabaseContainerAutoConfiguration.class);

    // Container type constants. Milvus, Qdrant, and Weaviate have shipped AI Fabric
    // vector provider modules; Chroma and pgvector are generic future-provider fixtures.
    private static final String CONTAINER_TYPE_MILVUS = "milvus";
    private static final String CONTAINER_TYPE_QDRANT = "qdrant";
    private static final String CONTAINER_TYPE_WEAVIATE = "weaviate";
    private static final String CONTAINER_TYPE_CHROMA = "chroma";
    private static final String CONTAINER_TYPE_PGVECTOR = "pgvector";

    // Property keys
    private static final String PROP_TESTCONTAINERS_ENABLED = "testcontainers.enabled";
    private static final String PROP_VECTOR_DB_TYPE = "ai.vector-db.type";
    private static final String PROP_IMAGE_VERSION_PREFIX = "testcontainers.";

    // Default Docker images
    private static final String DEFAULT_IMAGE_MILVUS = "milvusdb/milvus:v2.4.0";
    // Align with the official Qdrant Java client (gRPC) to avoid API/proto mismatches during tests.
    private static final String DEFAULT_IMAGE_QDRANT = "qdrant/qdrant:v1.16.1";
    private static final String DEFAULT_IMAGE_WEAVIATE = "semitechnologies/weaviate:1.23.0";
    private static final String DEFAULT_IMAGE_CHROMA = "chromadb/chroma:0.4.22";
    private static final String DEFAULT_IMAGE_PGVECTOR = "pgvector/pgvector:pg16";

    // Timeout constants
    private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration EXTENDED_STARTUP_TIMEOUT = Duration.ofMinutes(5);

    // Port constants
    private static final int PORT_MILVUS = 19530;
    private static final int PORT_QDRANT_REST = 6333;
    private static final int PORT_QDRANT_GRPC = 6334;
    private static final int PORT_WEAVIATE = 8080;
    private static final int PORT_CHROMA = 8000;
    private static final int PORT_POSTGRESQL = 5432;

    // Health check paths
    private static final String HEALTH_PATH_QDRANT = "/readyz";
    private static final String HEALTH_PATH_WEAVIATE = "/v1/.well-known/ready";
    private static final String HEALTH_PATH_CHROMA = "/api/v1/heartbeat";

    // Property source names
    private static final String PROP_SOURCE_MILVUS = "milvusContainerProperties";
    private static final String PROP_SOURCE_QDRANT = "qdrantContainerProperties";
    private static final String PROP_SOURCE_WEAVIATE = "weaviateContainerProperties";
    private static final String PROP_SOURCE_CHROMA = "chromaContainerProperties";
    private static final String PROP_SOURCE_PGVECTOR = "pgvectorContainerProperties";

    // Configuration property keys
    // Note: Services read from AIProviderConfig which uses prefix "ai.providers"
    // Vector database services (MilvusVectorDatabaseService, QdrantVectorDatabaseService, etc.)
    // call providerConfig.getMilvus() which reads from ai.providers.milvus.*
    // Spring Boot's relaxed binding allows both kebab-case (database-name) and camelCase (databaseName)
    private static final String PROP_KEY_MILVUS_HOST = "ai.providers.milvus.host";
    private static final String PROP_KEY_MILVUS_PORT = "ai.providers.milvus.port";
    private static final String PROP_KEY_MILVUS_DATABASE_NAME = "ai.providers.milvus.database-name";
    private static final String PROP_KEY_MILVUS_USERNAME = "ai.providers.milvus.username";
    private static final String PROP_KEY_MILVUS_PASSWORD = "ai.providers.milvus.password";
    private static final String PROP_KEY_MILVUS_SECURE = "ai.providers.milvus.secure";
    private static final String PROP_KEY_MILVUS_ENABLED = "ai.providers.milvus.enabled";

    private static final String PROP_KEY_QDRANT_HOST = "ai.providers.qdrant.host";
    private static final String PROP_KEY_QDRANT_PORT = "ai.providers.qdrant.port";
    private static final String PROP_KEY_QDRANT_GRPC_PORT = "ai.providers.qdrant.grpc-port";
    private static final String PROP_KEY_QDRANT_API_KEY = "ai.providers.qdrant.api-key";
    private static final String PROP_KEY_QDRANT_PREFER_GRPC = "ai.providers.qdrant.prefer-grpc";
    private static final String PROP_KEY_QDRANT_ENABLED = "ai.providers.qdrant.enabled";

    private static final String PROP_KEY_WEAVIATE_SCHEME = "ai.providers.weaviate.scheme";
    private static final String PROP_KEY_WEAVIATE_HOST = "ai.providers.weaviate.host";
    private static final String PROP_KEY_WEAVIATE_PORT = "ai.providers.weaviate.port";
    private static final String PROP_KEY_WEAVIATE_API_KEY = "ai.providers.weaviate.api-key";
    private static final String PROP_KEY_WEAVIATE_ENABLED = "ai.providers.weaviate.enabled";

    private static final String PROP_KEY_CHROMA_HOST = "ai.providers.chroma.host";
    private static final String PROP_KEY_CHROMA_PORT = "ai.providers.chroma.port";
    private static final String PROP_KEY_CHROMA_ENABLED = "ai.providers.chroma.enabled";

    private static final String PROP_KEY_PGVECTOR_HOST = "ai.providers.pgvector.host";
    private static final String PROP_KEY_PGVECTOR_PORT = "ai.providers.pgvector.port";
    private static final String PROP_KEY_PGVECTOR_DATABASE = "ai.providers.pgvector.database";
    private static final String PROP_KEY_PGVECTOR_USERNAME = "ai.providers.pgvector.username";
    private static final String PROP_KEY_PGVECTOR_PASSWORD = "ai.providers.pgvector.password";

    // Default values
    private static final String DEFAULT_DATABASE_MILVUS = "default";
    private static final String DEFAULT_DATABASE_PGVECTOR = "vectordb";
    private static final String DEFAULT_USERNAME_PGVECTOR = "test";
    private static final String DEFAULT_PASSWORD_PGVECTOR = "test";
    private static final String DEFAULT_SCHEME_WEAVIATE = "http";
    private static final String DEFAULT_INIT_SCRIPT_PGVECTOR = "db/init-pgvector.sql";

    // Environment variable keys
    private static final String ENV_WEAVIATE_AUTH_ANONYMOUS = "AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED";
    private static final String ENV_WEAVIATE_PERSISTENCE_PATH = "PERSISTENCE_DATA_PATH";
    private static final String ENV_WEAVIATE_VECTORIZER = "DEFAULT_VECTORIZER_MODULE";
    private static final String ENV_WEAVIATE_CLUSTER_HOSTNAME = "CLUSTER_HOSTNAME";
    private static final String ENV_CHROMA_PERSISTENT = "IS_PERSISTENT";
    private static final String ENV_CHROMA_TELEMETRY = "ANONYMIZED_TELEMETRY";

    // Environment variable values
    private static final String ENV_VALUE_WEAVIATE_AUTH_ANONYMOUS = "true";
    private static final String ENV_VALUE_WEAVIATE_PERSISTENCE_PATH = "/var/lib/weaviate";
    private static final String ENV_VALUE_WEAVIATE_VECTORIZER = "none";
    private static final String ENV_VALUE_WEAVIATE_CLUSTER_HOSTNAME = "node1";
    private static final String ENV_VALUE_CHROMA_PERSISTENT = "TRUE";
    private static final String ENV_VALUE_CHROMA_TELEMETRY = "FALSE";

    // Thread-safe container storage (for parallel test execution)
    private static final Map<String, GenericContainer<?>> activeContainers = new ConcurrentHashMap<>();

    // ==================== MILVUS ====================

    /**
     * Creates and starts a Milvus container when {@code ai.vector-db.type=milvus}.
     *
     * <p>Injects the following properties into the Spring environment:
     * <ul>
     *   <li>{@code ai.providers.milvus.host}</li>
     *   <li>{@code ai.providers.milvus.port}</li>
     *   <li>{@code ai.providers.milvus.database-name}</li>
     *   <li>{@code ai.providers.milvus.username}</li>
     *   <li>{@code ai.providers.milvus.password}</li>
     *   <li>{@code ai.providers.milvus.secure}</li>
     *   <li>{@code ai.providers.milvus.enabled}</li>
     * </ul>
     * </p>
     *
     * @param environment Spring environment for property injection
     * @return Started Milvus container
     * @throws IllegalStateException if container fails to start
     */
    @Bean
    @ConditionalOnProperty(name = PROP_VECTOR_DB_TYPE, havingValue = CONTAINER_TYPE_MILVUS)
    public GenericContainer<?> milvusContainer(ConfigurableEnvironment environment) {
        // First check if container was started early by TestcontainersInitializer
        GenericContainer<?> earlyStarted = TestcontainersInitializer.getEarlyStartedContainer(CONTAINER_TYPE_MILVUS);
        if (earlyStarted != null && earlyStarted.isRunning()) {
            log.info("Reusing Milvus container started early by TestcontainersInitializer at {}:{}",
                earlyStarted.getHost(), earlyStarted.getMappedPort(PORT_MILVUS));
            activeContainers.put(CONTAINER_TYPE_MILVUS, earlyStarted);
            return earlyStarted;
        }

        // Fallback: check if already started by this bean method
        if (activeContainers.containsKey(CONTAINER_TYPE_MILVUS)) {
            GenericContainer<?> existing = activeContainers.get(CONTAINER_TYPE_MILVUS);
            if (existing != null && existing.isRunning()) {
                log.info("Reusing existing Milvus container at {}:{}",
                    existing.getHost(), existing.getMappedPort(PORT_MILVUS));
                return existing;
            }
        }

        log.info("Starting Milvus container via Testcontainers auto-configuration...");

        try {
            String image = getImageVersion(CONTAINER_TYPE_MILVUS, DEFAULT_IMAGE_MILVUS);
            GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(image)
            )
                .withExposedPorts(PORT_MILVUS)
                .withCommand("milvus", "run", "standalone")
                .withEnv("COMMON_STORAGETYPE", "local")
                .withEnv("ETCD_USE_EMBED", "true")
                .withEnv("MINIO_ADDRESS", "localhost")
                .withStartupTimeout(EXTENDED_STARTUP_TIMEOUT)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(EXTENDED_STARTUP_TIMEOUT));

            container.start();
            activeContainers.put(CONTAINER_TYPE_MILVUS, container);

            Map<String, Object> properties = new HashMap<>();
            properties.put(PROP_KEY_MILVUS_HOST, container.getHost());
            properties.put(PROP_KEY_MILVUS_PORT, container.getMappedPort(PORT_MILVUS));
            properties.put(PROP_KEY_MILVUS_DATABASE_NAME, DEFAULT_DATABASE_MILVUS);
            properties.put(PROP_KEY_MILVUS_USERNAME, "");
            properties.put(PROP_KEY_MILVUS_PASSWORD, "");
            properties.put(PROP_KEY_MILVUS_SECURE, false);
            properties.put(PROP_KEY_MILVUS_ENABLED, true);

            environment.getPropertySources().addFirst(
                new MapPropertySource(PROP_SOURCE_MILVUS, properties)
            );

            log.info("Milvus container started successfully at {}:{}",
                container.getHost(), container.getMappedPort(PORT_MILVUS));

            return container;
        } catch (Exception e) {
            log.error("Failed to start Milvus container", e);
            throw new IllegalStateException("Failed to start Milvus container: " + e.getMessage(), e);
        }
    }

    // ==================== QDRANT ====================

    /**
     * Creates and starts a Qdrant container when {@code ai.vector-db.type=qdrant}.
     *
     * <p>Injects the following properties into the Spring environment:
     * <ul>
     *   <li>{@code ai.providers.qdrant.host}</li>
     *   <li>{@code ai.providers.qdrant.port}</li>
     *   <li>{@code ai.providers.qdrant.grpc-port}</li>
     *   <li>{@code ai.providers.qdrant.api-key}</li>
     *   <li>{@code ai.providers.qdrant.prefer-grpc}</li>
     *   <li>{@code ai.providers.qdrant.enabled}</li>
     * </ul>
     * </p>
     *
     * @param environment Spring environment for property injection
     * @return Started Qdrant container
     * @throws IllegalStateException if container fails to start
     */
    @Bean
    @ConditionalOnProperty(name = PROP_VECTOR_DB_TYPE, havingValue = CONTAINER_TYPE_QDRANT)
    public GenericContainer<?> qdrantContainer(ConfigurableEnvironment environment) {
        GenericContainer<?> earlyStarted = TestcontainersInitializer.getEarlyStartedContainer(CONTAINER_TYPE_QDRANT);
        if (earlyStarted != null && earlyStarted.isRunning()) {
            log.info("Reusing Qdrant container started early by TestcontainersInitializer at {}:{}",
                earlyStarted.getHost(), earlyStarted.getMappedPort(PORT_QDRANT_REST));
            activeContainers.put(CONTAINER_TYPE_QDRANT, earlyStarted);
            return earlyStarted;
        }

        if (activeContainers.containsKey(CONTAINER_TYPE_QDRANT)) {
            GenericContainer<?> existing = activeContainers.get(CONTAINER_TYPE_QDRANT);
            if (existing != null && existing.isRunning()) {
                log.info("Reusing existing Qdrant container at {}:{}",
                    existing.getHost(), existing.getMappedPort(PORT_QDRANT_REST));
                return existing;
            }
        }

        log.info("Starting Qdrant container...");

        try {
            String image = getImageVersion(CONTAINER_TYPE_QDRANT, DEFAULT_IMAGE_QDRANT);
            GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(image)
            )
                .withExposedPorts(PORT_QDRANT_REST, PORT_QDRANT_GRPC)
                .waitingFor(Wait.forHttp(HEALTH_PATH_QDRANT)
                    .forPort(PORT_QDRANT_REST)
                    .forStatusCode(200)
                    .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT));

            container.start();
            activeContainers.put(CONTAINER_TYPE_QDRANT, container);

            Map<String, Object> properties = new HashMap<>();
            properties.put(PROP_KEY_QDRANT_HOST, container.getHost());
            properties.put(PROP_KEY_QDRANT_PORT, container.getMappedPort(PORT_QDRANT_REST));
            properties.put(PROP_KEY_QDRANT_GRPC_PORT, container.getMappedPort(PORT_QDRANT_GRPC));
            properties.put(PROP_KEY_QDRANT_API_KEY, "");
            properties.put(PROP_KEY_QDRANT_PREFER_GRPC, false);
            properties.put(PROP_KEY_QDRANT_ENABLED, true);

            environment.getPropertySources().addFirst(
                new MapPropertySource(PROP_SOURCE_QDRANT, properties)
            );

            log.info("Qdrant container started successfully at {}:{}",
                container.getHost(), container.getMappedPort(PORT_QDRANT_REST));

            return container;
        } catch (Exception e) {
            log.error("Failed to start Qdrant container", e);
            throw new IllegalStateException("Failed to start Qdrant container: " + e.getMessage(), e);
        }
    }

    // ==================== WEAVIATE ====================

    /**
     * Creates and starts a Weaviate container when {@code ai.vector-db.type=weaviate}.
     *
     * <p>Injects the following properties into the Spring environment:
     * <ul>
     *   <li>{@code ai.providers.weaviate.scheme}</li>
     *   <li>{@code ai.providers.weaviate.host}</li>
     *   <li>{@code ai.providers.weaviate.port}</li>
     *   <li>{@code ai.providers.weaviate.api-key}</li>
     *   <li>{@code ai.providers.weaviate.enabled}</li>
     * </ul>
     * </p>
     *
     * @param environment Spring environment for property injection
     * @return Started Weaviate container
     * @throws IllegalStateException if container fails to start
     */
    @Bean
    @ConditionalOnProperty(name = PROP_VECTOR_DB_TYPE, havingValue = CONTAINER_TYPE_WEAVIATE)
    public GenericContainer<?> weaviateContainer(ConfigurableEnvironment environment) {
        GenericContainer<?> earlyStarted = TestcontainersInitializer.getEarlyStartedContainer(CONTAINER_TYPE_WEAVIATE);
        if (earlyStarted != null && earlyStarted.isRunning()) {
            log.info("Reusing Weaviate container started early by TestcontainersInitializer at {}:{}",
                earlyStarted.getHost(), earlyStarted.getMappedPort(PORT_WEAVIATE));
            activeContainers.put(CONTAINER_TYPE_WEAVIATE, earlyStarted);
            return earlyStarted;
        }

        if (activeContainers.containsKey(CONTAINER_TYPE_WEAVIATE)) {
            GenericContainer<?> existing = activeContainers.get(CONTAINER_TYPE_WEAVIATE);
            if (existing != null && existing.isRunning()) {
                log.info("Reusing existing Weaviate container at {}:{}",
                    existing.getHost(), existing.getMappedPort(PORT_WEAVIATE));
                return existing;
            }
        }

        log.info("Starting Weaviate container...");

        try {
            String image = getImageVersion(CONTAINER_TYPE_WEAVIATE, DEFAULT_IMAGE_WEAVIATE);
            GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(image)
            )
                .withExposedPorts(PORT_WEAVIATE)
                .withEnv(ENV_WEAVIATE_AUTH_ANONYMOUS, ENV_VALUE_WEAVIATE_AUTH_ANONYMOUS)
                .withEnv(ENV_WEAVIATE_PERSISTENCE_PATH, ENV_VALUE_WEAVIATE_PERSISTENCE_PATH)
                .withEnv(ENV_WEAVIATE_VECTORIZER, ENV_VALUE_WEAVIATE_VECTORIZER)
                .withEnv(ENV_WEAVIATE_CLUSTER_HOSTNAME, ENV_VALUE_WEAVIATE_CLUSTER_HOSTNAME)
                .waitingFor(Wait.forHttp(HEALTH_PATH_WEAVIATE)
                    .forPort(PORT_WEAVIATE)
                    .forStatusCode(200)
                    .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT));

            container.start();
            activeContainers.put(CONTAINER_TYPE_WEAVIATE, container);

            Map<String, Object> properties = new HashMap<>();
            properties.put(PROP_KEY_WEAVIATE_SCHEME, DEFAULT_SCHEME_WEAVIATE);
            properties.put(PROP_KEY_WEAVIATE_HOST, container.getHost());
            properties.put(PROP_KEY_WEAVIATE_PORT, container.getMappedPort(PORT_WEAVIATE));
            properties.put(PROP_KEY_WEAVIATE_API_KEY, "");
            properties.put(PROP_KEY_WEAVIATE_ENABLED, true);

            environment.getPropertySources().addFirst(
                new MapPropertySource(PROP_SOURCE_WEAVIATE, properties)
            );

            log.info("Weaviate container started successfully at {}:{}",
                container.getHost(), container.getMappedPort(PORT_WEAVIATE));

            return container;
        } catch (Exception e) {
            log.error("Failed to start Weaviate container", e);
            throw new IllegalStateException("Failed to start Weaviate container: " + e.getMessage(), e);
        }
    }

    // ==================== CHROMA ====================

    /**
     * Creates and starts a Chroma container when {@code ai.vector-db.type=chroma}.
     *
     * <p>Injects the following properties into the Spring environment:
     * <ul>
     *   <li>{@code ai.providers.chroma.host}</li>
     *   <li>{@code ai.providers.chroma.port}</li>
     *   <li>{@code ai.providers.chroma.enabled}</li>
     * </ul>
     * </p>
     *
     * @param environment Spring environment for property injection
     * @return Started Chroma container
     * @throws IllegalStateException if container fails to start
     */
    @Bean
    @ConditionalOnProperty(name = PROP_VECTOR_DB_TYPE, havingValue = CONTAINER_TYPE_CHROMA)
    public GenericContainer<?> chromaContainer(ConfigurableEnvironment environment) {
        if (activeContainers.containsKey(CONTAINER_TYPE_CHROMA)) {
            GenericContainer<?> existing = activeContainers.get(CONTAINER_TYPE_CHROMA);
            if (existing != null && existing.isRunning()) {
                log.info("Reusing existing Chroma container at {}:{}",
                    existing.getHost(), existing.getMappedPort(PORT_CHROMA));
                return existing;
            }
        }

        log.info("Starting Chroma container...");

        try {
            String image = getImageVersion(CONTAINER_TYPE_CHROMA, DEFAULT_IMAGE_CHROMA);
            GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(image)
            )
                .withExposedPorts(PORT_CHROMA)
                .withEnv(ENV_CHROMA_PERSISTENT, ENV_VALUE_CHROMA_PERSISTENT)
                .withEnv(ENV_CHROMA_TELEMETRY, ENV_VALUE_CHROMA_TELEMETRY)
                .waitingFor(Wait.forHttp(HEALTH_PATH_CHROMA)
                    .forPort(PORT_CHROMA)
                    .forStatusCode(200)
                    .withStartupTimeout(DEFAULT_STARTUP_TIMEOUT));

            container.start();
            activeContainers.put(CONTAINER_TYPE_CHROMA, container);

            Map<String, Object> properties = new HashMap<>();
            properties.put(PROP_KEY_CHROMA_HOST, container.getHost());
            properties.put(PROP_KEY_CHROMA_PORT, container.getMappedPort(PORT_CHROMA));
            properties.put(PROP_KEY_CHROMA_ENABLED, true);

            environment.getPropertySources().addFirst(
                new MapPropertySource(PROP_SOURCE_CHROMA, properties)
            );

            log.info("Chroma container started successfully at {}:{}",
                container.getHost(), container.getMappedPort(PORT_CHROMA));

            return container;
        } catch (Exception e) {
            log.error("Failed to start Chroma container", e);
            throw new IllegalStateException("Failed to start Chroma container: " + e.getMessage(), e);
        }
    }

    // ==================== PGVECTOR ====================

    /**
     * Creates and starts a PostgreSQL with pgvector container when {@code ai.vector-db.type=pgvector}.
     *
     * <p>Injects the following properties into the Spring environment:
     * <ul>
     *   <li>{@code ai.providers.pgvector.host}</li>
     *   <li>{@code ai.providers.pgvector.port}</li>
     *   <li>{@code ai.providers.pgvector.database}</li>
     *   <li>{@code ai.providers.pgvector.username}</li>
     *   <li>{@code ai.providers.pgvector.password}</li>
     * </ul>
     * </p>
     *
     * @param environment Spring environment for property injection
     * @return Started PostgreSQL container with pgvector extension
     * @throws IllegalStateException if container fails to start
     */
    @Bean
    @ConditionalOnProperty(name = PROP_VECTOR_DB_TYPE, havingValue = CONTAINER_TYPE_PGVECTOR)
    public PostgreSQLContainer<?> pgvectorContainer(ConfigurableEnvironment environment) {
        if (activeContainers.containsKey(CONTAINER_TYPE_PGVECTOR)) {
            GenericContainer<?> existing = activeContainers.get(CONTAINER_TYPE_PGVECTOR);
            if (existing != null && existing.isRunning()) {
                log.info("Reusing existing pgvector container at {}:{}",
                    existing.getHost(), existing.getMappedPort(PORT_POSTGRESQL));
                return (PostgreSQLContainer<?>) existing;
            }
        }

        log.info("Starting PostgreSQL with pgvector container...");

        try {
            String image = getImageVersion(CONTAINER_TYPE_PGVECTOR, DEFAULT_IMAGE_PGVECTOR);
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse(image)
                    .asCompatibleSubstituteFor("postgres")
            )
                .withDatabaseName(DEFAULT_DATABASE_PGVECTOR)
                .withUsername(DEFAULT_USERNAME_PGVECTOR)
                .withPassword(DEFAULT_PASSWORD_PGVECTOR)
                .withInitScript(DEFAULT_INIT_SCRIPT_PGVECTOR)
                .withStartupTimeout(EXTENDED_STARTUP_TIMEOUT);

            container.start();
            activeContainers.put(CONTAINER_TYPE_PGVECTOR, container);

            Map<String, Object> properties = new HashMap<>();
            properties.put(PROP_KEY_PGVECTOR_HOST, container.getHost());
            properties.put(PROP_KEY_PGVECTOR_PORT, container.getMappedPort(PORT_POSTGRESQL));
            properties.put(PROP_KEY_PGVECTOR_DATABASE, DEFAULT_DATABASE_PGVECTOR);
            properties.put(PROP_KEY_PGVECTOR_USERNAME, DEFAULT_USERNAME_PGVECTOR);
            properties.put(PROP_KEY_PGVECTOR_PASSWORD, DEFAULT_PASSWORD_PGVECTOR);

            environment.getPropertySources().addFirst(
                new MapPropertySource(PROP_SOURCE_PGVECTOR, properties)
            );

            log.info("pgvector container started successfully at {}:{}",
                container.getHost(), container.getMappedPort(PORT_POSTGRESQL));

            return container;
        } catch (Exception e) {
            log.error("Failed to start pgvector container", e);
            throw new IllegalStateException("Failed to start pgvector container: " + e.getMessage(), e);
        }
    }

    // ==================== CLEANUP ====================

    /**
     * Cleans up all active containers when the application context is destroyed.
     *
     * <p>This method is called automatically by Spring when the test context shuts down.
     * All containers are stopped and removed from the active containers map.</p>
     */
    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up vector database containers...");
        activeContainers.values().forEach(container -> {
            try {
                if (container != null && container.isRunning()) {
                    log.debug("Stopping container: {}", container.getContainerId());
                    container.stop();
                    TestcontainersInitializer.removeEarlyStartedContainer(container);
                }
            } catch (Exception e) {
                log.warn("Error stopping container: {}", e.getMessage());
            }
        });
        activeContainers.clear();
        log.info("Container cleanup completed");
    }

    /**
     * Utility method to get container image version from system properties.
     *
     * <p>Allows overriding default images via system properties:
     * <ul>
     *   <li>{@code testcontainers.milvus.image}</li>
     *   <li>{@code testcontainers.qdrant.image}</li>
     *   <li>{@code testcontainers.weaviate.image}</li>
     *   <li>{@code testcontainers.chroma.image}</li>
     *   <li>{@code testcontainers.pgvector.image}</li>
     * </ul>
     * </p>
     *
     * @param provider Provider name (e.g., "milvus", "qdrant")
     * @param defaultImage Default Docker image to use if not overridden
     * @return Docker image name (either from system property or default)
     */
    private String getImageVersion(String provider, String defaultImage) {
        String propertyKey = PROP_IMAGE_VERSION_PREFIX + provider + ".image";
        String customImage = System.getProperty(propertyKey);
        return customImage != null ? customImage : defaultImage;
    }
}
