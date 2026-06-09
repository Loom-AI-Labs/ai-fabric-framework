package com.ai.fabric.realapps.migrationcatalog;

import com.ai.infrastructure.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class MigrationEnabledProductCatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationEnabledProductCatalogApplication.class, args);
    }
}
