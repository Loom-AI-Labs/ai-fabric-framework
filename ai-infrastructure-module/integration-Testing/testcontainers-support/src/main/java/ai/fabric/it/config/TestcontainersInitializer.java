package ai.fabric.it.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Initializer that enables Testcontainers when a module-backed vector database
 * container is selected for a test run.
 *
 * <p>This initializer checks if:
 * <ul>
 *   <li>The {@code ai.vector-db.type} is set to a shipped, module-backed container type.</li>
 *   <li>Either the {@code testcontainers} Spring profile is active, or the vector
 *       database type was explicitly provided through {@code -Dai.vector-db.type=...}
 *       or {@code VECTOR_DB_TYPE}.</li>
 *   <li>
 *       Milvus, Qdrant, and Weaviate are backed by shipped AI Fabric vector
 *       provider modules. Chroma and pgvector are generic fixtures retained
 *       for future provider modules and are not auto-enabled by this initializer.</li>
 * </ul>
 * If these conditions are met, it starts the supported container early and sets
 * {@code testcontainers.enabled=true}.
 * This property is then used by {@link VectorDatabaseContainerAutoConfiguration}
 * to conditionally enable container auto-configuration.</p>
 *
 * <p><strong>Usage:</strong></p>
 * <pre>
 * // Unit tests - defaults to Lucene (fast, no containers)
 * mvn test
 *
 * // Use Testcontainers with Milvus
 * mvn test -Dspring.profiles.active=testcontainers -Dai.vector-db.type=milvus
 *
 * // Use Testcontainers with Qdrant
 * mvn test -Dspring.profiles.active=testcontainers -Dai.vector-db.type=qdrant
 * </pre>
 *
 * <p><strong>Module-backed Container Types:</strong> milvus, qdrant, weaviate</p>
 * <p><strong>Generic Future-provider Fixtures:</strong> chroma, pgvector. Use explicit
 * {@code testcontainers.enabled=true} with {@link VectorDatabaseContainerAutoConfiguration}
 * if a future-provider test needs one of these fixture containers.</p>
 *
 * <p><strong>Default Behavior:</strong> If no container type is specified or type is
 * lucene/memory, Testcontainers will not activate, and tests will use Lucene (fast).</p>
 *
 * <p><strong>Thread Safety:</strong> This class is stateless and thread-safe.</p>
 *
 * @author AI Infrastructure Team
 * @version 1.1.0
 * @see VectorDatabaseContainerAutoConfiguration
 */
public class TestcontainersInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Logger log = LoggerFactory.getLogger(TestcontainersInitializer.class);

    private static final String PROFILE_TESTCONTAINERS = "testcontainers";
    private static final String PROP_TESTCONTAINERS_ENABLED = "testcontainers.enabled";
    private static final String PROP_VECTOR_DB_TYPE = "ai.vector-db.type";
    private static final String PROP_SOURCE_TESTCONTAINERS_ENABLED = "testcontainersEnabled";

    // Auto-started vector database types. These have shipped AI Fabric provider modules.
    private static final String TYPE_MILVUS = "milvus";
    private static final String TYPE_QDRANT = "qdrant";
    private static final String TYPE_WEAVIATE = "weaviate";

    // Container constants (shared with VectorDatabaseContainerAutoConfiguration)
    private static final String DEFAULT_IMAGE_MILVUS = "milvusdb/milvus:v2.4.0";
    // Align with the official Qdrant Java client (gRPC) to avoid API/proto mismatches during tests.
    private static final String DEFAULT_IMAGE_QDRANT = "qdrant/qdrant:v1.16.1";
    private static final String DEFAULT_IMAGE_WEAVIATE = "semitechnologies/weaviate:1.23.0";
    private static final int PORT_MILVUS = 19530;
    private static final int PORT_QDRANT_REST = 6333;
    private static final int PORT_QDRANT_GRPC = 6334;
    private static final int PORT_WEAVIATE = 8080;
    private static final Duration EXTENDED_STARTUP_TIMEOUT = Duration.ofMinutes(5);
    private static final String DEFAULT_DATABASE_MILVUS = "default";
    private static final String HEALTH_PATH_QDRANT = "/readyz";
    private static final String HEALTH_PATH_WEAVIATE = "/v1/.well-known/ready";
    private static final String DEFAULT_SCHEME_WEAVIATE = "http";

    // Weaviate environment variables
    private static final String ENV_WEAVIATE_AUTH_ANONYMOUS = "AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED";
    private static final String ENV_WEAVIATE_PERSISTENCE_PATH = "PERSISTENCE_DATA_PATH";
    private static final String ENV_WEAVIATE_VECTORIZER = "DEFAULT_VECTORIZER_MODULE";
    private static final String ENV_WEAVIATE_CLUSTER_HOSTNAME = "CLUSTER_HOSTNAME";
    private static final String ENV_VALUE_WEAVIATE_AUTH_ANONYMOUS = "true";
    private static final String ENV_VALUE_WEAVIATE_PERSISTENCE_PATH = "/var/lib/weaviate";
    private static final String ENV_VALUE_WEAVIATE_VECTORIZER = "none";
    private static final String ENV_VALUE_WEAVIATE_CLUSTER_HOSTNAME = "node1";

    // Shared container storage (accessed by both initializer and bean methods)
    private static final Map<String, GenericContainer<?>> earlyStartedContainers = new ConcurrentHashMap<>();

    /**
     * Initializes the application context by enabling Testcontainers if a
     * module-backed vector database type is selected and either the
     * testcontainers profile is active or the type was explicitly specified
     * through the command line/environment.
     *
     * @param context The application context to initialize
     */
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment env = context.getEnvironment();

        // Check if testcontainers profile is active
        boolean testcontainersActive = false;
        for (String profile : env.getActiveProfiles()) {
            if (PROFILE_TESTCONTAINERS.equals(profile)) {
                testcontainersActive = true;
                break;
            }
        }

        log.debug("TestcontainersInitializer: Active profiles: {}", java.util.Arrays.toString(env.getActiveProfiles()));

        // Check if a shipped module-backed vector database type is specified.
        String vectorDbType = env.getProperty(PROP_VECTOR_DB_TYPE, String.class, "lucene");
        log.info("TestcontainersInitializer: Vector DB type: {}", vectorDbType);
        boolean isModuleBackedContainerType = isModuleBackedContainerType(vectorDbType);

        // Start containers if: (1) testcontainers profile is active, OR (2) a container type is explicitly specified
        // This allows tests to use containers even if they have @ActiveProfiles that don't include testcontainers
        if (isModuleBackedContainerType && (testcontainersActive || isExplicitlySpecified(env, vectorDbType))) {
            log.info("TestcontainersInitializer: Container type detected, starting container early...");
            Map<String, Object> props = new HashMap<>();
            props.put(PROP_TESTCONTAINERS_ENABLED, true);

            // Start container early and inject connection properties BEFORE any beans are created
            // This ensures services can connect immediately when they're instantiated
            startContainerEarly(vectorDbType, env, props);

            // Use addFirst to ensure highest priority - these properties will override YAML config
            env.getPropertySources().addFirst(
                new MapPropertySource(PROP_SOURCE_TESTCONTAINERS_ENABLED, props)
            );

            log.info("Testcontainers enabled for vector DB type: {}. Container started early.", vectorDbType);
        }
        // If not a module-backed container type (e.g., lucene, memory, chroma, pgvector),
        // Testcontainers stays disabled unless explicitly configured elsewhere.
        // Tests will use the specified type (Lucene by default)
    }

    /**
     * Starts the container early (before Spring beans are created) and injects connection properties.
     * This ensures services can connect immediately when instantiated.
     */
    private void startContainerEarly(String vectorDbType, ConfigurableEnvironment env, Map<String, Object> props) {
        String normalizedType = vectorDbType.toLowerCase().trim();

        if (TYPE_MILVUS.equals(normalizedType)) {
            startMilvusContainerEarly(env, props);
        } else if (TYPE_QDRANT.equals(normalizedType)) {
            startQdrantContainerEarly(env, props);
        } else if (TYPE_WEAVIATE.equals(normalizedType)) {
            startWeaviateContainerEarly(env, props);
        }
    }

    /**
     * Starts Milvus container early and injects connection properties.
     */
    private void startMilvusContainerEarly(ConfigurableEnvironment env, Map<String, Object> props) {
        if (earlyStartedContainers.containsKey(TYPE_MILVUS)) {
            GenericContainer<?> existing = earlyStartedContainers.get(TYPE_MILVUS);
            if (existing != null && existing.isRunning()) {
                log.info("Reusing existing Milvus container started early");
                injectMilvusProperties(existing, props);
                return;
            }
        }

        log.info("Starting Milvus container early (before Spring beans are created)...");
        verifyDockerAvailableOrFail("Milvus");
        try {
            // Wait a bit for Docker to be fully ready (especially on Windows)
            // This helps avoid connection issues when Docker Desktop is still initializing
            try {
                Thread.sleep(1000); // 1 second delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Milvus standalone requires specific environment variables and command
            GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(DEFAULT_IMAGE_MILVUS)
            )
                .withExposedPorts(PORT_MILVUS)
                .withCommand("milvus", "run", "standalone")
                .withEnv("COMMON_STORAGETYPE", "local")
                .withEnv("ETCD_USE_EMBED", "true")
                .withEnv("MINIO_ADDRESS", "localhost")
                .withStartupTimeout(EXTENDED_STARTUP_TIMEOUT)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(EXTENDED_STARTUP_TIMEOUT));

            log.info("Starting Milvus container (this may take a few minutes on first run)...");
            container.start();
            log.info("Milvus container started successfully");

            earlyStartedContainers.put(TYPE_MILVUS, container);

            injectMilvusProperties(container, props);

            log.info("Milvus container started early at {}:{}. Properties injected: {}",
                container.getHost(), container.getMappedPort(PORT_MILVUS), props.keySet());
        } catch (Exception e) {
            log.error("Failed to start Milvus container early", e);

            // Check if this is a Docker connectivity issue (common on Windows with Docker Desktop 29.x)
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("Could not find a valid Docker environment")) {
                log.error("""

                    ========================================================================
                    DOCKER CONNECTIVITY ISSUE DETECTED
                    ========================================================================
                    Testcontainers cannot connect to Docker Desktop. This is a known compatibility
                    issue between Testcontainers 1.19.3 and Docker Desktop 29.1.3 on Windows.

                    SOLUTIONS:
                    1. Use Lucene (no Docker required):
                       mvn test "-Dai.vector-db.type=lucene"

                    2. Update Testcontainers to a newer version (if available)

                    3. Try restarting Docker Desktop and wait until it's fully started

                    4. Check Docker Desktop settings:
                       - Ensure "Use the WSL 2 based engine" is enabled
                       - Try disabling and re-enabling Docker Desktop
                    ========================================================================
                    """);
            }

            throw new IllegalStateException("Failed to start Milvus container early: " + e.getMessage() +
                ". If you see Docker connectivity issues, try using Lucene instead: -Dai.vector-db.type=lucene", e);
        }
    }

    /**
     * Starts Qdrant container early and injects connection properties.
     */
    private void startQdrantContainerEarly(ConfigurableEnvironment env, Map<String, Object> props) {
        if (earlyStartedContainers.containsKey(TYPE_QDRANT)) {
            GenericContainer<?> existing = earlyStartedContainers.get(TYPE_QDRANT);
            if (existing != null && existing.isRunning()) {
                log.info("Reusing existing Qdrant container started early");
                injectQdrantProperties(existing, props);
                return;
            }
        }

        log.info("Starting Qdrant container early (before Spring beans are created)...");
        verifyDockerAvailableOrFail("Qdrant");
        try {
            try {
                Thread.sleep(1000); // 1 second delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(DEFAULT_IMAGE_QDRANT)
            )
                .withExposedPorts(PORT_QDRANT_REST, PORT_QDRANT_GRPC)
                .withStartupTimeout(EXTENDED_STARTUP_TIMEOUT)
                .waitingFor(Wait.forHttp(HEALTH_PATH_QDRANT)
                    .forPort(PORT_QDRANT_REST)
                    .forStatusCode(200)
                    .withStartupTimeout(EXTENDED_STARTUP_TIMEOUT));

            log.info("Starting Qdrant container (this may take a few minutes on first run)...");
            container.start();
            log.info("Qdrant container started successfully");

            earlyStartedContainers.put(TYPE_QDRANT, container);

            injectQdrantProperties(container, props);

            log.info("Qdrant container started early at {}:{}. Properties injected: {}",
                container.getHost(), container.getMappedPort(PORT_QDRANT_REST), props.keySet());
        } catch (Exception e) {
            log.error("Failed to start Qdrant container early", e);
            throw new IllegalStateException("Failed to start Qdrant container early: " + e.getMessage() +
                ". If you see Docker connectivity issues, try using Lucene instead: -Dai.vector-db.type=lucene", e);
        }
    }

    /**
     * Injects Milvus connection properties into the properties map.
     */
    private void injectMilvusProperties(GenericContainer<?> container, Map<String, Object> props) {
        props.put("ai.providers.milvus.host", container.getHost());
        props.put("ai.providers.milvus.port", container.getMappedPort(PORT_MILVUS));
        props.put("ai.providers.milvus.database-name", DEFAULT_DATABASE_MILVUS);
        props.put("ai.providers.milvus.username", "");
        props.put("ai.providers.milvus.password", "");
        props.put("ai.providers.milvus.secure", false);
        props.put("ai.providers.milvus.enabled", true);
    }

    /**
     * Starts Weaviate container early and injects connection properties.
     */
    private void startWeaviateContainerEarly(ConfigurableEnvironment env, Map<String, Object> props) {
        if (earlyStartedContainers.containsKey(TYPE_WEAVIATE)) {
            GenericContainer<?> existing = earlyStartedContainers.get(TYPE_WEAVIATE);
            if (existing != null && existing.isRunning()) {
                log.info("Reusing existing Weaviate container started early");
                injectWeaviateProperties(existing, props);
                return;
            }
        }

        log.info("Starting Weaviate container early (before Spring beans are created)...");
        verifyDockerAvailableOrFail("Weaviate");
        try {
            try {
                Thread.sleep(1000); // 1 second delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            GenericContainer<?> container = new GenericContainer<>(
                DockerImageName.parse(DEFAULT_IMAGE_WEAVIATE)
            )
                .withExposedPorts(PORT_WEAVIATE)
                .withEnv(ENV_WEAVIATE_AUTH_ANONYMOUS, ENV_VALUE_WEAVIATE_AUTH_ANONYMOUS)
                .withEnv(ENV_WEAVIATE_PERSISTENCE_PATH, ENV_VALUE_WEAVIATE_PERSISTENCE_PATH)
                .withEnv(ENV_WEAVIATE_VECTORIZER, ENV_VALUE_WEAVIATE_VECTORIZER)
                .withEnv(ENV_WEAVIATE_CLUSTER_HOSTNAME, ENV_VALUE_WEAVIATE_CLUSTER_HOSTNAME)
                .withStartupTimeout(EXTENDED_STARTUP_TIMEOUT)
                .waitingFor(Wait.forHttp(HEALTH_PATH_WEAVIATE)
                    .forPort(PORT_WEAVIATE)
                    .forStatusCode(200)
                    .withStartupTimeout(EXTENDED_STARTUP_TIMEOUT));

            log.info("Starting Weaviate container (this may take a few minutes on first run)...");
            container.start();
            log.info("Weaviate container started successfully");

            earlyStartedContainers.put(TYPE_WEAVIATE, container);

            injectWeaviateProperties(container, props);

            log.info("Weaviate container started early at {}:{}. Properties injected: {}",
                container.getHost(), container.getMappedPort(PORT_WEAVIATE), props.keySet());
        } catch (Exception e) {
            log.error("Failed to start Weaviate container early", e);
            throw new IllegalStateException("Failed to start Weaviate container early: " + e.getMessage() +
                ". If you see Docker connectivity issues, try using Lucene instead: -Dai.vector-db.type=lucene", e);
        }
    }

    /**
     * Injects Qdrant connection properties into the properties map.
     */
    private void injectQdrantProperties(GenericContainer<?> container, Map<String, Object> props) {
        props.put("ai.providers.qdrant.host", container.getHost());
        props.put("ai.providers.qdrant.port", container.getMappedPort(PORT_QDRANT_REST));
        props.put("ai.providers.qdrant.grpc-port", container.getMappedPort(PORT_QDRANT_GRPC));
        props.put("ai.providers.qdrant.api-key", "");
        props.put("ai.providers.qdrant.prefer-grpc", false);
        props.put("ai.providers.qdrant.enabled", true);
    }

    /**
     * Injects Weaviate connection properties into the properties map.
     */
    private void injectWeaviateProperties(GenericContainer<?> container, Map<String, Object> props) {
        props.put("ai.providers.weaviate.scheme", DEFAULT_SCHEME_WEAVIATE);
        props.put("ai.providers.weaviate.host", container.getHost());
        props.put("ai.providers.weaviate.port", container.getMappedPort(PORT_WEAVIATE));
        props.put("ai.providers.weaviate.api-key", "");
        props.put("ai.providers.weaviate.enabled", true);
    }

    /**
     * Verifies Docker availability before enabling a container-backed provider.
     * Failing fast avoids partially enabling Testcontainers without connection properties.
     */
    protected void verifyDockerAvailable() {
        org.testcontainers.DockerClientFactory.instance().client();
    }

    private void verifyDockerAvailableOrFail(String containerType) {
        try {
            verifyDockerAvailable();
        } catch (Exception e) {
            log.warn("Docker is unavailable for {} container startup: {}", containerType, e.getMessage());
            throw new IllegalStateException("Failed to start " + containerType + " container early: " + e.getMessage() +
                ". If you see Docker connectivity issues, try using Lucene instead: -Dai.vector-db.type=lucene", e);
        }
    }

    /**
     * Gets an early-started container (used by bean methods to reuse containers).
     */
    public static GenericContainer<?> getEarlyStartedContainer(String type) {
        return earlyStartedContainers.get(type);
    }

    static void removeEarlyStartedContainer(GenericContainer<?> container) {
        if (container == null) {
            return;
        }
        earlyStartedContainers.entrySet().removeIf(entry -> entry.getValue() == container);
    }

    /**
     * Checks if a container type was explicitly specified (via system property or environment variable).
     * This allows containers to start even if the testcontainers profile isn't active.
     */
    private boolean isExplicitlySpecified(ConfigurableEnvironment env, String vectorDbType) {
        // Check if it was set via system property (not from YAML default)
        String systemProp = System.getProperty(PROP_VECTOR_DB_TYPE);
        if (systemProp != null && systemProp.equalsIgnoreCase(vectorDbType)) {
            return true;
        }
        // Check if it was set via environment variable
        String envVar = System.getenv("VECTOR_DB_TYPE");
        if (envVar != null && envVar.equalsIgnoreCase(vectorDbType)) {
            return true;
        }
        return false;
    }

    /**
     * Checks if the specified vector database type has a shipped AI Fabric provider module
     * and can be auto-started by this initializer.
     *
     * @param type Vector database type to check
     * @return true if the type is module-backed and auto-startable, false otherwise
     */
    private boolean isModuleBackedContainerType(String type) {
        if (type == null) {
            return false;
        }
        String normalizedType = type.toLowerCase().trim();
        return TYPE_MILVUS.equals(normalizedType)
            || TYPE_QDRANT.equals(normalizedType)
            || TYPE_WEAVIATE.equals(normalizedType);
    }
}
