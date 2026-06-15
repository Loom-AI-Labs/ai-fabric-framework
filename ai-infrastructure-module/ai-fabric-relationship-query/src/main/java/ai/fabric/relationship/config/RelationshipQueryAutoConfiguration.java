package ai.fabric.relationship.config;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.core.AICoreService;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration entry point registered via {@code spring.factories}.
 */
@AutoConfiguration
@AutoConfigureAfter({AIInfrastructureAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@EnableConfigurationProperties(RelationshipQueryProperties.class)
@ConditionalOnClass({AICoreService.class, EntityManagerFactory.class})
@ConditionalOnProperty(
    prefix = "ai.infrastructure.relationship",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
@Import(RelationshipQueryConfiguration.class)
public class RelationshipQueryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RelationshipQueryAutoConfiguration.class);

    public RelationshipQueryAutoConfiguration(RelationshipQueryProperties properties) {
        log.info(
            "Relationship query module enabled (maxTraversalDepth={}, defaultReturnMode={}, vectorSearchEnabled={})",
            properties.getMaxTraversalDepth(),
            properties.getDefaultReturnMode(),
            properties.isEnableVectorSearch()
        );
    }
}
