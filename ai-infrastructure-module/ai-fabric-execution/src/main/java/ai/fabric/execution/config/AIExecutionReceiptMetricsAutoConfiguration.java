package ai.fabric.execution.config;

import ai.fabric.execution.action.ActionProposalMetrics;
import ai.fabric.execution.action.MicrometerActionProposalMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = AIExecutionReceiptAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(
    prefix = "ai.execution.receipts",
    name = "enabled",
    havingValue = "true"
)
public class AIExecutionReceiptMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(ActionProposalMetrics.class)
    public ActionProposalMetrics actionProposalMetrics(
        MeterRegistry registry
    ) {
        return new MicrometerActionProposalMetrics(registry);
    }
}
