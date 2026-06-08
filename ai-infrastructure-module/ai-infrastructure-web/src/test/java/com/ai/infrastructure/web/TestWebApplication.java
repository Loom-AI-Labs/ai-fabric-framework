package com.ai.infrastructure.web;

import com.ai.infrastructure.migration.config.MigrationAutoConfiguration;
import com.ai.infrastructure.migration.service.DataMigrationService;
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
    public com.ai.infrastructure.web.migration.MigrationController migrationController(DataMigrationService migrationService) {
        return new com.ai.infrastructure.web.migration.MigrationController(migrationService);
    }
}
