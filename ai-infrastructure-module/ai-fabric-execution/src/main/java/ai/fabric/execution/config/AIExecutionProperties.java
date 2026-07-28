package ai.fabric.execution.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai.execution")
public class AIExecutionProperties {

    private final Async async = new Async();
    private final Capabilities capabilities = new Capabilities();

    public Async getAsync() {
        return async;
    }

    public Capabilities getCapabilities() {
        return capabilities;
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
}
