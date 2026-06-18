package ai.fabric.it.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Ensures large JSONB columns are mapped to CLOB in the in-memory H2 database
 * so Behavioural integration tests can store full embeddings and insights payloads.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class H2JsonColumnCustomizer {

    private static final List<String> ALTER_STATEMENTS = List.of(
        "ALTER TABLE behavior_embeddings ALTER COLUMN embedding CLOB",
        "ALTER TABLE behavior_embeddings ALTER COLUMN original_text CLOB",
        "ALTER TABLE behavior_events ALTER COLUMN metadata CLOB",
        "ALTER TABLE behavior_insights ALTER COLUMN patterns CLOB",
        "ALTER TABLE behavior_insights ALTER COLUMN preferences CLOB",
        "ALTER TABLE behavior_insights ALTER COLUMN recommendations CLOB",
        "ALTER TABLE behavior_insights ALTER COLUMN scores CLOB",
        "ALTER TABLE behavior_alerts ALTER COLUMN context CLOB"
    );

    private final DataSource dataSource;

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @PostConstruct
    void customizeJsonColumns() {
        if (datasourceUrl == null || !datasourceUrl.startsWith("jdbc:h2")) {
            log.debug("Skipping H2 JSON column customization for datasource url {}", datasourceUrl);
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {
            for (String sql : ALTER_STATEMENTS) {
                try {
                    stmt.execute(sql);
                } catch (SQLException ex) {
                    log.debug("Ignoring statement '{}' because of: {}", sql, ex.getMessage());
                }
            }
        } catch (SQLException ex) {
            log.warn("Failed to apply H2 JSON column customizations", ex);
        }
    }
}
