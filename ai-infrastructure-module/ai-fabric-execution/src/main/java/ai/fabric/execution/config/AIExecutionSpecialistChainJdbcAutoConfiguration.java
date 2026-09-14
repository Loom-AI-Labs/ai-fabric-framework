package ai.fabric.execution.config;

import ai.fabric.execution.chain.state.JdbcSpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.SpecialistChainExecutionRepository;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Optional JDBC adapter for durable bounded specialist-chain state. */
@AutoConfiguration(before = AIExecutionSpecialistChainAutoConfiguration.class)
@EnableConfigurationProperties(AIExecutionProperties.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(
    prefix = "ai.execution.specialist-chains",
    name = {"enabled", "durable-enabled"},
    havingValue = "true"
)
public class AIExecutionSpecialistChainJdbcAutoConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(SpecialistChainExecutionRepository.class)
    public SpecialistChainExecutionRepository
        specialistChainExecutionRepository(
            DataSource dataSource,
            AIExecutionProperties properties
        ) {
        return new JdbcSpecialistChainExecutionRepository(
            dataSource,
            properties.getSpecialistChains().isInitializeSchema()
        );
    }
}
