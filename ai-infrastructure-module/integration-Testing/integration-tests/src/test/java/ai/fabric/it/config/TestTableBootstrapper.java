package ai.fabric.it.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures operational SQL tables exist for integration tests.
 *
 * <p>The framework no longer persists indexed content/metadata to SQL (vector DB is the source of truth).
 * We only bootstrap operational tables required by the indexing queue when test environments run
 * without schema auto-creation.</p>
 */
@Component
@Profile("disabled-test-table-bootstrapper")
@RequiredArgsConstructor
@Slf4j
public class TestTableBootstrapper {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void createOperationalTablesIfMissing() {
        createIndexingQueueIfAbsent();
    }

    private void createIndexingQueueIfAbsent() {
        // Minimal schema to satisfy integration tests that enqueue/dequeue work items.
        String ddl = """
            CREATE TABLE IF NOT EXISTS ai_indexing_queue (
                id IDENTITY PRIMARY KEY,
                entity_type VARCHAR(128) NOT NULL,
                entity_id VARCHAR(255) NOT NULL,
                status VARCHAR(64) NOT NULL,
                priority INTEGER DEFAULT 0,
                requested_at TIMESTAMP,
                scheduled_for TIMESTAMP,
                processing_started_at TIMESTAMP,
                processed_at TIMESTAMP,
                updated_at TIMESTAMP,
                payload CLOB,
                error_message CLOB
            );
            CREATE INDEX IF NOT EXISTS idx_ai_idx_queue_entity ON ai_indexing_queue(entity_type, entity_id);
            CREATE INDEX IF NOT EXISTS idx_ai_idx_queue_status ON ai_indexing_queue(status, priority, scheduled_for);
            """;

        try {
            jdbcTemplate.execute(ddl);
            log.debug("Ensured AI indexing queue table exists");
        } catch (Exception ex) {
            log.warn("Skipping bootstrap for ai_indexing_queue due to existing definition: {}", ex.getMessage());
        }
    }
}
