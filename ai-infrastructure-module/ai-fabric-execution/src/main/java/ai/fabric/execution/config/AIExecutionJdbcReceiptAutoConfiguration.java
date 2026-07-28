package ai.fabric.execution.config;

import ai.fabric.execution.action.ActionProposalReceiptRepository;
import ai.fabric.execution.action.JdbcActionProposalReceiptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(
    prefix = "ai.execution.receipts",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class AIExecutionJdbcReceiptAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "ai.execution.receipts",
        name = "repository",
        havingValue = "JDBC",
        matchIfMissing = true
    )
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(ActionProposalReceiptRepository.class)
    public ActionProposalReceiptRepository jdbcActionProposalReceiptRepository(
        DataSource dataSource,
        ObjectMapper objectMapper,
        AIExecutionProperties properties
    ) {
        if (properties.getReceipts().getRepository()
            != AIExecutionProperties.ReceiptRepository.JDBC) {
            throw new IllegalStateException(
                "JDBC receipt auto-configuration requires repository=JDBC"
            );
        }
        return new JdbcActionProposalReceiptRepository(
            dataSource,
            objectMapper,
            properties.getReceipts().isInitializeSchema()
        );
    }
}
