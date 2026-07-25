package ai.fabric.storage.auto;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TableAutoCreationServiceTest {

    @Test
    void unsupportedDatabaseTypeFailsAsInvalidConfiguration() throws Exception {
        TableAutoCreationService service = new TableAutoCreationService(mock(DataSource.class));
        Method generator = TableAutoCreationService.class
            .getDeclaredMethod("generateCreateIndexingQueueSQL", String.class, String.class);
        generator.setAccessible(true);

        assertThatThrownBy(() -> generator.invoke(service, "ORACLE", "ai_indexing_queue"))
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasRootCauseMessage("Database ORACLE not supported for indexing queue auto-create.");
    }

    @Test
    void createsCurrentQueueAndOrderingStateSchema() {
        DriverManagerDataSource dataSource = dataSource("current");
        TableAutoCreationService service = new TableAutoCreationService(dataSource);

        service.createTablesAtStartup();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'AI_INDEXING_QUEUE' "
                + "AND COLUMN_NAME IN ('WORK_TYPE', 'DESCRIPTOR_HASH', 'DEPENDS_ON_WORK_ID')",
            Integer.class
        )).isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'AI_INDEXING_ENTITY_STATE' "
                + "AND COLUMN_NAME IN ('STATE_KEY', 'LAST_APPLIED_WORK_ID', 'LAST_SOURCE_VERSION')",
            Integer.class
        )).isEqualTo(3);
    }

    @Test
    void rejectsPreCutoverQueueInsteadOfSilentlyUsingIt() {
        DriverManagerDataSource dataSource = dataSource("legacy");
        new JdbcTemplate(dataSource).execute("""
            CREATE TABLE ai_indexing_queue (
                id VARCHAR(36) PRIMARY KEY,
                entity_type VARCHAR(128) NOT NULL
            )
            """);
        TableAutoCreationService service = new TableAutoCreationService(dataSource);

        assertThatThrownBy(service::createTablesAtStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .satisfies(exception -> assertThat(exception.getCause())
                .hasMessageContaining("Incompatible ai_indexing_queue schema")
                .hasMessageContaining("Remove the pre-0.4 indexing tables"));
    }

    @Test
    void rejectsQueueMissingAnyMappedCutoverColumn() {
        DriverManagerDataSource dataSource = dataSource("partial-queue");
        TableAutoCreationService service = new TableAutoCreationService(dataSource);
        service.createTablesAtStartup();
        new JdbcTemplate(dataSource).execute(
            "ALTER TABLE ai_indexing_queue DROP COLUMN correlation_id"
        );

        assertThatThrownBy(service::createTablesAtStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .satisfies(exception -> assertThat(exception.getCause())
                .hasMessageContaining("Incompatible ai_indexing_queue schema")
                .hasMessageContaining("correlation_id"));
    }

    @Test
    void rejectsOrderingStateMissingSourceVersionColumn() {
        DriverManagerDataSource dataSource = dataSource("partial-state");
        TableAutoCreationService service = new TableAutoCreationService(dataSource);
        service.createTablesAtStartup();
        new JdbcTemplate(dataSource).execute(
            "ALTER TABLE ai_indexing_entity_state DROP COLUMN last_source_version"
        );

        assertThatThrownBy(service::createTablesAtStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .satisfies(exception -> assertThat(exception.getCause())
                .hasMessageContaining("Incompatible ai_indexing_entity_state schema")
                .hasMessageContaining("last_source_version"));
    }

    private DriverManagerDataSource dataSource(String name) {
        return new DriverManagerDataSource(
            "jdbc:h2:mem:table-auto-" + name + ";DB_CLOSE_DELAY=-1",
            "sa",
            ""
        );
    }
}
