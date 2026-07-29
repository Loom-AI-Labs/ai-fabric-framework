package ai.fabric.execution.state;

import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.specialist.SpecialistId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC durable execution state with optimistic compare-and-set transitions.
 */
public final class JdbcDurableExecutionRepository
    implements DurableExecutionRepository {

    private static final String TABLE = "ai_specialist_execution";
    private static final String SELECT_COLUMNS = """
        invocation_id, specialist_name, specialist_version,
        specialist_content_hash, access_fingerprint,
        idempotency_fingerprint, request_fingerprint,
        protected_request, protected_result, status, failure_reason,
        deadline, created_at, updated_at, completed_at, expires_at,
        lease_owner, lease_until, attempt_count, version
        """;

    private final JdbcTemplate jdbc;

    public JdbcDurableExecutionRepository(
        DataSource dataSource,
        boolean initializeSchema
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        if (initializeSchema) {
            initializeSchema();
        }
    }

    @Override
    public DurableExecutionRecord create(DurableExecutionRecord record) {
        try {
            jdbc.update(
                """
                INSERT INTO ai_specialist_execution (
                  invocation_id, specialist_name, specialist_version,
                  specialist_content_hash, access_fingerprint,
                  idempotency_fingerprint, request_fingerprint,
                  protected_request, protected_result, status, failure_reason,
                  deadline, created_at, updated_at, completed_at, expires_at,
                  lease_owner, lease_until, attempt_count, version
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                record.invocationId(),
                record.specialistId().name(),
                record.specialistId().version(),
                record.specialistContentHash(),
                record.accessFingerprint(),
                record.idempotencyFingerprint(),
                record.requestFingerprint(),
                record.protectedRequest(),
                record.protectedResult(),
                record.status().name(),
                record.failureReason(),
                timestamp(record.deadline()),
                timestamp(record.createdAt()),
                timestamp(record.updatedAt()),
                timestamp(record.completedAt()),
                timestamp(record.expiresAt()),
                record.leaseOwner(),
                timestamp(record.leaseUntil()),
                record.attemptCount(),
                record.version()
            );
            return record;
        } catch (DuplicateKeyException ex) {
            throw new DuplicateExecutionException(
                "Duplicate invocation ID or scoped idempotency fingerprint",
                ex
            );
        }
    }

    @Override
    public Optional<DurableExecutionRecord> findById(
        String invocationId
    ) {
        return queryOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE invocation_id = ?",
            invocationId
        );
    }

    @Override
    public Optional<DurableExecutionRecord> findByIdempotencyFingerprint(
        String idempotencyFingerprint
    ) {
        if (idempotencyFingerprint == null) {
            return Optional.empty();
        }
        return queryOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE idempotency_fingerprint = ?",
            idempotencyFingerprint
        );
    }

    @Override
    public boolean compareAndSet(
        DurableExecutionRecord expected,
        DurableExecutionRecord updated
    ) {
        if (!expected.invocationId().equals(updated.invocationId())
            || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                "Execution transition must preserve ID and increment version"
            );
        }
        int rows = jdbc.update(
            """
            UPDATE ai_specialist_execution
               SET protected_result = ?,
                   status = ?,
                   failure_reason = ?,
                   updated_at = ?,
                   completed_at = ?,
                   expires_at = ?,
                   lease_owner = ?,
                   lease_until = ?,
                   attempt_count = ?,
                   version = ?
             WHERE invocation_id = ?
               AND status = ?
               AND version = ?
               AND (
                 lease_owner = ?
                 OR (lease_owner IS NULL AND ? IS NULL)
               )
            """,
            updated.protectedResult(),
            updated.status().name(),
            updated.failureReason(),
            timestamp(updated.updatedAt()),
            timestamp(updated.completedAt()),
            timestamp(updated.expiresAt()),
            updated.leaseOwner(),
            timestamp(updated.leaseUntil()),
            updated.attemptCount(),
            updated.version(),
            expected.invocationId(),
            expected.status().name(),
            expected.version(),
            expected.leaseOwner(),
            expected.leaseOwner()
        );
        return rows == 1;
    }

    @Override
    public List<DurableExecutionRecord> findRecoverable(
        Instant now,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status = ?"
                + " OR (status = ? AND lease_until <= ?)"
                + " ORDER BY updated_at ASC LIMIT ?",
            this::map,
            ExecutionHandleStatus.QUEUED.name(),
            ExecutionHandleStatus.RUNNING.name(),
            timestamp(now),
            positiveLimit(limit)
        );
    }

    @Override
    public List<DurableExecutionRecord> findTerminalCompletedBefore(
        Instant cutoff,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status IN (?, ?, ?, ?, ?)"
                + " AND completed_at < ?"
                + " ORDER BY completed_at ASC LIMIT ?",
            this::map,
            ExecutionHandleStatus.SUCCEEDED.name(),
            ExecutionHandleStatus.FAILED.name(),
            ExecutionHandleStatus.CANCELLED.name(),
            ExecutionHandleStatus.REJECTED.name(),
            ExecutionHandleStatus.EXPIRED.name(),
            timestamp(cutoff),
            positiveLimit(limit)
        );
    }

    @Override
    public boolean delete(DurableExecutionRecord expected) {
        return jdbc.update(
            "DELETE FROM " + TABLE
                + " WHERE invocation_id = ? AND status = ? AND version = ?",
            expected.invocationId(),
            expected.status().name(),
            expected.version()
        ) == 1;
    }

    private Optional<DurableExecutionRecord> queryOne(
        String sql,
        Object value
    ) {
        return jdbc.query(sql, this::map, value).stream().findFirst();
    }

    private DurableExecutionRecord map(ResultSet resultSet, int row)
        throws SQLException {
        return new DurableExecutionRecord(
            resultSet.getString("invocation_id"),
            SpecialistId.of(
                resultSet.getString("specialist_name"),
                resultSet.getString("specialist_version")
            ),
            resultSet.getString("specialist_content_hash"),
            resultSet.getString("access_fingerprint"),
            resultSet.getString("idempotency_fingerprint"),
            resultSet.getString("request_fingerprint"),
            resultSet.getString("protected_request"),
            resultSet.getString("protected_result"),
            ExecutionHandleStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("failure_reason"),
            instant(resultSet, "deadline"),
            instant(resultSet, "created_at"),
            instant(resultSet, "updated_at"),
            instant(resultSet, "completed_at"),
            instant(resultSet, "expires_at"),
            resultSet.getString("lease_owner"),
            instant(resultSet, "lease_until"),
            resultSet.getInt("attempt_count"),
            resultSet.getLong("version")
        );
    }

    private void initializeSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_specialist_execution (
              invocation_id VARCHAR(120) PRIMARY KEY,
              specialist_name VARCHAR(120) NOT NULL,
              specialist_version VARCHAR(80) NOT NULL,
              specialist_content_hash VARCHAR(64) NOT NULL,
              access_fingerprint VARCHAR(64) NOT NULL,
              idempotency_fingerprint VARCHAR(64) NULL UNIQUE,
              request_fingerprint VARCHAR(64) NOT NULL,
              protected_request TEXT NOT NULL,
              protected_result TEXT NULL,
              status VARCHAR(40) NOT NULL,
              failure_reason VARCHAR(160) NULL,
              deadline TIMESTAMP NOT NULL,
              created_at TIMESTAMP NOT NULL,
              updated_at TIMESTAMP NOT NULL,
              completed_at TIMESTAMP NULL,
              expires_at TIMESTAMP NOT NULL,
              lease_owner VARCHAR(160) NULL,
              lease_until TIMESTAMP NULL,
              attempt_count INTEGER NOT NULL,
              version BIGINT NOT NULL
            )
            """);
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_execution_recovery"
                + " ON " + TABLE + " (status, lease_until, updated_at)"
        );
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_execution_expiry"
                + " ON " + TABLE + " (completed_at)"
        );
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_execution_access"
                + " ON " + TABLE + " (access_fingerprint)"
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(ResultSet resultSet, String column)
        throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private int positiveLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return limit;
    }
}
