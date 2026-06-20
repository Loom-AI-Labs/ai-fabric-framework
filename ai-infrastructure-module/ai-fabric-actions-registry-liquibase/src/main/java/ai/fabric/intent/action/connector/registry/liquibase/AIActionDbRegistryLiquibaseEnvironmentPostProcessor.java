package ai.fabric.intent.action.connector.registry.liquibase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ensures Spring Boot's Liquibase auto-config does not fail when this optional module is present.
 *
 * <p>If the host application already configures a Liquibase changelog or provides a default master
 * changelog at {@code classpath:/db/changelog/db.changelog-master.*}, this post-processor does nothing.
 * Explicit {@code spring.liquibase.enabled=false} is also respected.</p>
 *
 * <p>Otherwise:
 * <ul>
 *   <li>When {@code ai.actions.db.enabled=true} and {@code ai.actions.db.liquibase.enabled=true} (default),
 *   it sets {@code spring.liquibase.change-log} to the Actions Registry changelog.</li>
 *   <li>When the DB registry is disabled (or Liquibase is disabled for it), it sets {@code spring.liquibase.enabled=false}
 *   to avoid boot-time failures due to a missing master changelog.</li>
 * </ul></p>
 */
public class AIActionDbRegistryLiquibaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_AI_ACTIONS_DB_ENABLED = "ai.actions.db.enabled";
    private static final String PROPERTY_AI_ACTIONS_DB_LIQUIBASE_ENABLED = "ai.actions.db.liquibase.enabled";
    private static final String PROPERTY_AI_ACTIONS_DB_LIQUIBASE_CHANGE_LOG = "ai.actions.db.liquibase.change-log";

    private static final String PROPERTY_SPRING_LIQUIBASE_ENABLED = "spring.liquibase.enabled";
    private static final String PROPERTY_SPRING_LIQUIBASE_CHANGE_LOG = "spring.liquibase.change-log";

    private static final String DEFAULT_ACTIONS_CHANGE_LOG = "classpath:db/changelog/ai-actions-registry-changelog.yaml";

    private static final String[] BOOT_DEFAULT_CHANGELOG_LOCATIONS = {
        "classpath:/db/changelog/db.changelog-master.yaml",
        "classpath:/db/changelog/db.changelog-master.yml",
        "classpath:/db/changelog/db.changelog-master.xml",
        "classpath:/db/changelog/db.changelog-master.json"
    };

    // Must run after ConfigDataEnvironmentPostProcessor so application.yml can set ai.actions.db.*.
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 25;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment == null) {
            return;
        }

        if (StringUtils.hasText(environment.getProperty(PROPERTY_SPRING_LIQUIBASE_CHANGE_LOG))
            || bootDefaultChangelogExists()
            || explicitSpringLiquibaseDisabled(environment)) {
            return;
        }

        boolean dbEnabled = Boolean.parseBoolean(environment.getProperty(PROPERTY_AI_ACTIONS_DB_ENABLED, "false"));
        boolean liquibaseEnabled = Boolean.parseBoolean(environment.getProperty(PROPERTY_AI_ACTIONS_DB_LIQUIBASE_ENABLED, "true"));

        Map<String, Object> defaults = new LinkedHashMap<>();
        if (dbEnabled && liquibaseEnabled) {
            String changeLog = environment.getProperty(PROPERTY_AI_ACTIONS_DB_LIQUIBASE_CHANGE_LOG);
            if (!StringUtils.hasText(changeLog)) {
                changeLog = DEFAULT_ACTIONS_CHANGE_LOG;
            }
            defaults.put(PROPERTY_SPRING_LIQUIBASE_CHANGE_LOG, changeLog.trim());
            defaults.put(PROPERTY_SPRING_LIQUIBASE_ENABLED, "true");
        } else {
            defaults.put(PROPERTY_SPRING_LIQUIBASE_ENABLED, "false");
        }

        MutablePropertySources sources = environment.getPropertySources();
        sources.addLast(new MapPropertySource("ai-actions-db-registry-liquibase-defaults", defaults));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private boolean bootDefaultChangelogExists() {
        ResourceLoader loader = new DefaultResourceLoader(getClass().getClassLoader());
        for (String location : BOOT_DEFAULT_CHANGELOG_LOCATIONS) {
            Resource resource = loader.getResource(location);
            if (resource != null && resource.exists()) {
                return true;
            }
        }
        return false;
    }

    private boolean explicitSpringLiquibaseDisabled(ConfigurableEnvironment environment) {
        if (environment == null || !environment.containsProperty(PROPERTY_SPRING_LIQUIBASE_ENABLED)) {
            return false;
        }
        return !Boolean.parseBoolean(environment.getProperty(PROPERTY_SPRING_LIQUIBASE_ENABLED, "true"));
    }
}
