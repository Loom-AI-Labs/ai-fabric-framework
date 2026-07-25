package ai.fabric.storage.auto;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Auto-creates essential SQL tables used by the AI infrastructure modules.
 *
 * <p>This service only creates operational tables that are required for the framework to run
 * (e.g., indexing queue). The framework does not persist indexed content/metadata to SQL.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TableAutoCreationService {

    private static final String INDEXING_QUEUE_TABLE = "ai_indexing_queue";
    private static final String INDEXING_STATE_TABLE = "ai_indexing_entity_state";
    private static final Set<String> REQUIRED_QUEUE_COLUMNS = Set.of(
        "id",
        "entity_type",
        "entity_id",
        "work_type",
        "source_operation",
        "strategy",
        "status",
        "payload_schema_version",
        "descriptor_hash",
        "correlation_id",
        "depends_on_work_id",
        "payload",
        "result_payload",
        "max_retries",
        "retry_count",
        "error_code",
        "dead_letter_reason",
        "processing_node",
        "requested_at",
        "scheduled_for",
        "started_at",
        "completed_at",
        "visibility_timeout_until",
        "last_error_at",
        "created_at",
        "updated_at",
        "version"
    );
    private static final Set<String> REQUIRED_STATE_COLUMNS = Set.of(
        "state_key",
        "entity_type",
        "entity_id",
        "last_applied_work_id",
        "last_source_version",
        "updated_at",
        "version"
    );

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void createTablesAtStartup() {
        try {
            String dbType = detectDatabaseType();
            log.info("Detected database type: {}", dbType);
            createIndexingQueueTable(dbType);
            createIndexingEntityStateTable(dbType);
        } catch (Exception ex) {
            log.error("Failed to auto-create AI infrastructure tables", ex);
            throw new IllegalStateException("Auto-table creation failed", ex);
        }
    }

    private void createIndexingQueueTable(String dbType) throws SQLException {
        String tableName = INDEXING_QUEUE_TABLE;
        if (tableExists(tableName)) {
            validateTableColumns(tableName, REQUIRED_QUEUE_COLUMNS);
            log.debug("Indexing queue table {} is compatible", tableName);
            return;
        }
        executeSql(generateCreateIndexingQueueSQL(dbType, tableName));
        log.info("Created AI indexing queue table {}", tableName);
    }

    private void createIndexingEntityStateTable(String dbType) throws SQLException {
        String tableName = INDEXING_STATE_TABLE;
        if (tableExists(tableName)) {
            validateTableColumns(tableName, REQUIRED_STATE_COLUMNS);
            log.debug("Indexing entity state table {} is compatible", tableName);
            return;
        }
        executeSql(generateCreateIndexingEntityStateSQL(dbType, tableName));
        log.info("Created AI indexing entity state table {}", tableName);
    }

    private String generateCreateIndexingQueueSQL(String dbType, String tableName) {
        String normalized = dbType != null ? dbType.toUpperCase() : "UNKNOWN";
        return switch (normalized) {
            case "MYSQL" -> """
                CREATE TABLE %s (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    entity_type VARCHAR(128) NOT NULL,
                    entity_id VARCHAR(512) NOT NULL,
                    work_type VARCHAR(32) NOT NULL,
                    source_operation VARCHAR(32) NOT NULL,
                    strategy VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    payload_schema_version INT NOT NULL,
                    descriptor_hash VARCHAR(64) NOT NULL,
                    correlation_id VARCHAR(128),
                    depends_on_work_id BIGINT,
                    payload LONGTEXT NOT NULL,
                    result_payload LONGTEXT,
                    max_retries INT NOT NULL,
                    retry_count INT NOT NULL,
                    error_code VARCHAR(128),
                    dead_letter_reason VARCHAR(256),
                    processing_node VARCHAR(128),
                    requested_at TIMESTAMP NOT NULL,
                    scheduled_for TIMESTAMP NOT NULL,
                    started_at TIMESTAMP NULL,
                    completed_at TIMESTAMP NULL,
                    visibility_timeout_until TIMESTAMP NULL,
                    last_error_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    INDEX idx_ai_queue_status_strategy (status, strategy, scheduled_for),
                    INDEX idx_ai_queue_entity_order (entity_type, entity_id, id),
                    INDEX idx_ai_queue_dependency (depends_on_work_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.formatted(tableName);
            case "POSTGRESQL" -> queueTableSql(
                tableName,
                "BIGSERIAL PRIMARY KEY",
                "TEXT"
            );
            case "H2" -> queueTableSql(
                tableName,
                "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY",
                "TEXT"
            );
            case "SQLITE" -> queueTableSql(
                tableName,
                "INTEGER PRIMARY KEY AUTOINCREMENT",
                "TEXT"
            );
            default -> throw new IllegalArgumentException(
                "Database " + dbType + " not supported for indexing queue auto-create.");
        };
    }

    private String queueTableSql(String tableName, String idDefinition, String textType) {
        return """
                CREATE TABLE %s (
                    id %s,
                    entity_type VARCHAR(128) NOT NULL,
                    entity_id VARCHAR(512) NOT NULL,
                    work_type VARCHAR(32) NOT NULL,
                    source_operation VARCHAR(32) NOT NULL,
                    strategy VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    payload_schema_version INT NOT NULL,
                    descriptor_hash VARCHAR(64) NOT NULL,
                    correlation_id VARCHAR(128),
                    depends_on_work_id BIGINT,
                    payload %s NOT NULL,
                    result_payload %s,
                    max_retries INT NOT NULL,
                    retry_count INT NOT NULL,
                    error_code VARCHAR(128),
                    dead_letter_reason VARCHAR(256),
                    processing_node VARCHAR(128),
                    requested_at TIMESTAMP NOT NULL,
                    scheduled_for TIMESTAMP NOT NULL,
                    started_at TIMESTAMP NULL,
                    completed_at TIMESTAMP NULL,
                    visibility_timeout_until TIMESTAMP NULL,
                    last_error_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0
                );
                CREATE INDEX idx_ai_queue_status_strategy ON %s(status, strategy, scheduled_for);
                CREATE INDEX idx_ai_queue_entity_order ON %s(entity_type, entity_id, id);
                CREATE INDEX idx_ai_queue_dependency ON %s(depends_on_work_id);
                """.formatted(
                    tableName,
                    idDefinition,
                    textType,
                    textType,
                    tableName,
                    tableName,
                    tableName
                );
    }

    private String generateCreateIndexingEntityStateSQL(
        String dbType,
        String tableName
    ) {
        String normalized = dbType != null ? dbType.toUpperCase() : "UNKNOWN";
        return switch (normalized) {
            case "MYSQL" -> """
                CREATE TABLE %s (
                    state_key VARCHAR(64) PRIMARY KEY NOT NULL,
                    entity_type VARCHAR(128) NOT NULL,
                    entity_id VARCHAR(512) NOT NULL,
                    last_applied_work_id BIGINT NOT NULL DEFAULT 0,
                    last_source_version BIGINT,
                    updated_at TIMESTAMP NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    INDEX idx_ai_state_entity (entity_type, entity_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """.formatted(tableName);
            case "POSTGRESQL", "H2", "SQLITE" -> """
                CREATE TABLE %s (
                    state_key VARCHAR(64) PRIMARY KEY NOT NULL,
                    entity_type VARCHAR(128) NOT NULL,
                    entity_id VARCHAR(512) NOT NULL,
                    last_applied_work_id BIGINT NOT NULL DEFAULT 0,
                    last_source_version BIGINT,
                    updated_at TIMESTAMP NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0
                );
                CREATE INDEX idx_ai_state_entity ON %s(entity_type, entity_id);
                """.formatted(tableName, tableName);
            default -> throw new IllegalArgumentException(
                "Database " + dbType + " not supported for indexing state auto-create.");
        };
    }

    private void validateTableColumns(
        String tableName,
        Set<String> requiredColumns
    ) throws SQLException {
        Set<String> actualColumns = tableColumns(tableName);
        Set<String> missing = new TreeSet<>(requiredColumns);
        missing.removeAll(actualColumns);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "Incompatible " + tableName + " schema; missing columns " + missing
                    + ". Remove the pre-0.4 indexing tables and rebuild generated index state."
            );
        }
    }

    private Set<String> tableColumns(String tableName) throws SQLException {
        Set<String> columns = new TreeSet<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            readColumns(metadata, tableName, columns);
            if (columns.isEmpty()) {
                readColumns(metadata, tableName.toUpperCase(Locale.ROOT), columns);
            }
        }
        return columns;
    }

    private void readColumns(
        DatabaseMetaData metadata,
        String tableName,
        Set<String> columns
    ) throws SQLException {
        try (var result = metadata.getColumns(null, null, tableName, null)) {
            while (result.next()) {
                String columnName = result.getString("COLUMN_NAME");
                if (columnName != null) {
                    columns.add(columnName.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (var tables = meta.getTables(null, null, tableName, null)) {
                if (tables.next()) {
                    return true;
                }
            }
            try (var tables = meta.getTables(null, null, tableName.toUpperCase(), null)) {
                return tables.next();
            }
        }
    }

    private void executeSql(String sql) throws SQLException {
        String[] statements = sql.split(";");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String statement : statements) {
                if (statement == null || statement.trim().isEmpty()) {
                    continue;
                }
                try {
                    stmt.execute(statement);
                } catch (SQLException ex) {
                    String message = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                    if (message.contains("already exists")) {
                        log.debug("Skipping statement because object already exists: {}", message);
                        continue;
                    }
                    throw ex;
                }
            }
        }
    }

    private String detectDatabaseType() {
        try (Connection conn = dataSource.getConnection()) {
            return normalizeDatabaseType(conn.getMetaData().getDatabaseProductName());
        } catch (SQLException ex) {
            log.warn("Could not detect database type", ex);
            return "UNKNOWN";
        }
    }

    private String normalizeDatabaseType(String productName) {
        if (productName == null) {
            return "UNKNOWN";
        }
        String normalized = productName.toUpperCase();
        if (normalized.contains("MYSQL") || normalized.contains("MARIADB") || normalized.contains("PERCONA")) {
            return "MYSQL";
        }
        if (normalized.contains("POSTGRES")) {
            return "POSTGRESQL";
        }
        if (normalized.contains("SQL SERVER") || normalized.contains("MSSQL") || normalized.contains("AZURE SQL")) {
            return "SQLSERVER";
        }
        if (normalized.contains("ORACLE")) {
            return "ORACLE";
        }
        if (normalized.contains("H2")) {
            return "H2";
        }
        if (normalized.contains("SQLITE")) {
            return "SQLITE";
        }
        if (normalized.contains("DB2")) {
            return "DB2";
        }
        if (normalized.contains("DERBY")) {
            return "DERBY";
        }
        if (normalized.contains("SYBASE")) {
            return "SYBASE";
        }
        return "UNKNOWN";
    }
}
