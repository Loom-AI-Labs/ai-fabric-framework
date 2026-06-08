package com.ai.infrastructure.intent.action.connector.registry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.actions.db")
public class AIActionDbRegistryProperties {

    /**
     * Enables DB-backed connector action registration and loading.
     */
    private boolean enabled = false;

    /**
     * API key protection for the registry endpoints.
     *
     * <p>Production guidance: keep enabled and set a strong value. If you use Spring Security,
     * disable this and enforce auth via a SecurityFilterChain.</p>
     */
    private ApiKeyProperties apiKey = new ApiKeyProperties();

    /**
     * Liquibase integration for the registry schema.
     *
     * <p>When using the optional module {@code ai-infrastructure-actions-registry-liquibase}, these properties
     * are used to provide safe defaults for {@code spring.liquibase.*} when the host application does not already
     * configure Liquibase.</p>
     */
    private LiquibaseProperties liquibase = new LiquibaseProperties();

    @Data
    public static class ApiKeyProperties {
        /**
         * When true, requests to {@code /api/ai/actions/registry/**} must include a matching API key header.
         */
        private boolean enabled = true;

        /**
         * Header name to validate.
         */
        private String header = "X-AIFABRIC-API-KEY";

        /**
         * Required API key value.
         */
        private String value;
    }

    @Data
    public static class LiquibaseProperties {
        /**
         * Enables Liquibase defaults for the DB action registry.
         */
        private boolean enabled = true;

        /**
         * Changelog location to apply when Liquibase is not otherwise configured.
         */
        private String changeLog = "classpath:db/changelog/ai-actions-registry-changelog.yaml";
    }
}
