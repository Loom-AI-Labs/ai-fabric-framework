package ai.fabric.web;

import ai.fabric.migration.config.MigrationAutoConfiguration;
import ai.fabric.migration.service.DataMigrationService;
import org.mockito.Mockito;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = MigrationAutoConfiguration.class)
public class TestWebApplication {

    @Bean
    public DataMigrationService dataMigrationService() {
        return Mockito.mock(DataMigrationService.class);
    }

    @Bean
    public ai.fabric.web.migration.MigrationController migrationController(DataMigrationService migrationService) {
        return new ai.fabric.web.migration.MigrationController(migrationService);
    }
}
