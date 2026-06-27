package ai.fabric.web.config;

import ai.fabric.migration.service.DataMigrationService;
import ai.fabric.web.migration.MigrationController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigureAfter(AIWebAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnClass(name = {
    "org.springframework.web.servlet.DispatcherServlet",
    "ai.fabric.migration.service.DataMigrationService"
})
@ConditionalOnProperty(prefix = "ai.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AIWebMigrationAutoConfiguration {

    @Bean
    @ConditionalOnBean(DataMigrationService.class)
    @ConditionalOnMissingBean(MigrationController.class)
    @ConditionalOnProperty(prefix = "ai.web.controllers", name = "migration", havingValue = "true", matchIfMissing = true)
    public MigrationController migrationController(DataMigrationService migrationService) {
        return new MigrationController(migrationService);
    }
}
