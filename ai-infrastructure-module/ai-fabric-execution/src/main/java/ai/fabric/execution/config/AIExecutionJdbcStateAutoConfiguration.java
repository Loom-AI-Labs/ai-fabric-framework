package ai.fabric.execution.config;

import ai.fabric.execution.state.DurableExecutionRepository;
import ai.fabric.execution.state.DurableExecutionSecurity;
import ai.fabric.execution.state.JdbcDurableExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration(before = AIExecutionAutoConfiguration.class)
@EnableConfigurationProperties(AIExecutionProperties.class)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(
    prefix = "ai.execution.async",
    name = "repository",
    havingValue = "JDBC"
)
public class AIExecutionJdbcStateAutoConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(DurableExecutionRepository.class)
    public DurableExecutionRepository durableExecutionRepository(
        DataSource dataSource,
        AIExecutionProperties properties
    ) {
        return new JdbcDurableExecutionRepository(
            dataSource,
            properties.getAsync().isInitializeSchema()
        );
    }

    @Bean
    @ConditionalOnMissingBean(DurableExecutionSecurity.class)
    public DurableExecutionSecurity durableExecutionSecurity(
        ObjectMapper objectMapper,
        AIExecutionProperties properties
    ) {
        return new DurableExecutionSecurity(
            objectMapper,
            properties.getAsync().getEncryptionSecret(),
            properties.getAsync().getFingerprintSecret()
        );
    }
}
