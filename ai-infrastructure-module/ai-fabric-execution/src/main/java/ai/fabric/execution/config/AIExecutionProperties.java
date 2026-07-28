package ai.fabric.execution.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai.execution")
public class AIExecutionProperties {

    private final Async async = new Async();
    private final Capabilities capabilities = new Capabilities();
    private final Receipts receipts = new Receipts();

    public Async getAsync() {
        return async;
    }

    public Capabilities getCapabilities() {
        return capabilities;
    }

    public Receipts getReceipts() {
        return receipts;
    }

    public static class Async {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 32;
        private Duration resultTtl = Duration.ofMinutes(15);

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = positive(corePoolSize, "corePoolSize");
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = positive(maxPoolSize, "maxPoolSize");
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            if (queueCapacity < 0) {
                throw new IllegalArgumentException("queueCapacity must not be negative");
            }
            this.queueCapacity = queueCapacity;
        }

        public Duration getResultTtl() {
            return resultTtl;
        }

        public void setResultTtl(Duration resultTtl) {
            if (resultTtl == null || resultTtl.isZero() || resultTtl.isNegative()) {
                throw new IllegalArgumentException("resultTtl must be positive");
            }
            this.resultTtl = resultTtl;
        }

        private int positive(int value, String field) {
            if (value < 1) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }
    }

    public static class Capabilities {
        private Set<String> registeredVectorSpaces = Set.of();
        private Set<String> allowedActions = Set.of();

        public Set<String> getRegisteredVectorSpaces() {
            return registeredVectorSpaces;
        }

        public void setRegisteredVectorSpaces(Set<String> registeredVectorSpaces) {
            this.registeredVectorSpaces = immutable(registeredVectorSpaces);
        }

        public Set<String> getAllowedActions() {
            return allowedActions;
        }

        public void setAllowedActions(Set<String> allowedActions) {
            this.allowedActions = immutable(allowedActions);
        }

        private Set<String> immutable(Set<String> values) {
            return values == null || values.isEmpty()
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(values));
        }
    }

    public static class Receipts {
        private boolean enabled;
        private ReceiptRepository repository = ReceiptRepository.JDBC;
        private boolean initializeSchema = true;
        private Duration ttl = Duration.ofMinutes(10);
        private Duration staleExecutingAfter = Duration.ofMinutes(2);
        private int recoveryBatchSize = 100;
        private boolean cleanupEnabled;
        private Duration retention = Duration.ofDays(90);
        private String encryptionSecret;
        private String fingerprintSecret;
        private boolean allowInProduction;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ReceiptRepository getRepository() {
            return repository;
        }

        public void setRepository(ReceiptRepository repository) {
            this.repository = repository == null
                ? ReceiptRepository.JDBC
                : repository;
        }

        public boolean isInitializeSchema() {
            return initializeSchema;
        }

        public void setInitializeSchema(boolean initializeSchema) {
            this.initializeSchema = initializeSchema;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = positive(ttl, "ttl");
        }

        public Duration getStaleExecutingAfter() {
            return staleExecutingAfter;
        }

        public void setStaleExecutingAfter(Duration staleExecutingAfter) {
            this.staleExecutingAfter = positive(
                staleExecutingAfter,
                "staleExecutingAfter"
            );
        }

        public int getRecoveryBatchSize() {
            return recoveryBatchSize;
        }

        public void setRecoveryBatchSize(int recoveryBatchSize) {
            if (recoveryBatchSize < 1) {
                throw new IllegalArgumentException(
                    "recoveryBatchSize must be positive"
                );
            }
            this.recoveryBatchSize = recoveryBatchSize;
        }

        public boolean isCleanupEnabled() {
            return cleanupEnabled;
        }

        public void setCleanupEnabled(boolean cleanupEnabled) {
            this.cleanupEnabled = cleanupEnabled;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = positive(retention, "retention");
        }

        public String getEncryptionSecret() {
            return encryptionSecret;
        }

        public void setEncryptionSecret(String encryptionSecret) {
            this.encryptionSecret = encryptionSecret;
        }

        public String getFingerprintSecret() {
            return fingerprintSecret;
        }

        public void setFingerprintSecret(String fingerprintSecret) {
            this.fingerprintSecret = fingerprintSecret;
        }

        public boolean isAllowInProduction() {
            return allowInProduction;
        }

        public void setAllowInProduction(boolean allowInProduction) {
            this.allowInProduction = allowInProduction;
        }

        private Duration positive(Duration value, String field) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(field + " must be positive");
            }
            return value;
        }
    }

    public enum ReceiptRepository {
        JDBC,
        IN_MEMORY
    }
}
