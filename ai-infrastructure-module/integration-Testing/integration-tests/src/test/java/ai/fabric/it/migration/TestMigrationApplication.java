package ai.fabric.it.migration;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
    basePackages = "ai.fabric",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.ai\\.infrastructure\\.it\\..*"
    )
)
@EntityScan(basePackages = {
    "ai.fabric.entity",
    "ai.fabric.migration.domain",
    "ai.fabric.it.entity",
    "ai.fabric.it.migration"
})
@EnableJpaRepositories(basePackages = {
    "ai.fabric.repository",
    "ai.fabric.migration.repository",
    "ai.fabric.it.repository",
    "ai.fabric.it.migration"
})
@Profile("migration-test")
public class TestMigrationApplication {
}
