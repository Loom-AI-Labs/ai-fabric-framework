package ai.fabric.execution.config;

import ai.fabric.execution.review.persistence.JdbcReviewDispatchRepository;
import ai.fabric.execution.review.persistence.JdbcReviewTaskRepository;
import ai.fabric.execution.review.persistence.ReviewDispatchRepository;
import ai.fabric.execution.review.persistence.ReviewTaskRepository;
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
    prefix = "ai.execution.reviews",
    name = "enabled",
    havingValue = "true"
)
public class AIExecutionJdbcReviewAutoConfiguration {

    @Bean
    @ConditionalOnProperty(
        prefix = "ai.execution.reviews",
        name = "repository",
        havingValue = "JDBC",
        matchIfMissing = true
    )
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(ReviewTaskRepository.class)
    public ReviewTaskRepository jdbcReviewTaskRepository(
        DataSource dataSource,
        ObjectMapper objectMapper,
        AIExecutionProperties properties
    ) {
        return new JdbcReviewTaskRepository(
            dataSource,
            objectMapper,
            properties.getReviews().isInitializeSchema()
        );
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "ai.execution.reviews",
        name = "repository",
        havingValue = "JDBC",
        matchIfMissing = true
    )
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(ReviewDispatchRepository.class)
    public ReviewDispatchRepository jdbcReviewDispatchRepository(
        DataSource dataSource,
        AIExecutionProperties properties
    ) {
        return new JdbcReviewDispatchRepository(
            dataSource,
            properties.getReviews().isInitializeSchema()
        );
    }
}
