package ai.fabric.execution.chain.state;

import ai.fabric.execution.chain.SpecialistChainExecutionStatus;
import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.specialist.SpecialistId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;

/** JDBC chain checkpoints with optimistic state and lease transitions. */
public final class JdbcSpecialistChainExecutionRepository
    implements SpecialistChainExecutionRepository {

    private static final String TABLE = "ai_specialist_chain_execution";
    private static final String SELECT_COLUMNS = """
        execution_id, chain_name, chain_version, chain_content_hash,
        manager_name, manager_version, manager_content_hash,
        access_fingerprint, idempotency_fingerprint, request_fingerprint,
        protected_request, protected_checkpoint, protected_result,
        status, failure_reason, next_decision_index, deadline,
        created_at, updated_at, completed_at, expires_at,
        lease_owner, lease_until, attempt_count, version
        """;

    private final JdbcTemplate jdbc;

    public JdbcSpecialistChainExecutionRepository(
        DataSource dataSource,
        boolean initializeSchema
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        if (initializeSchema) {
            initializeSchema();
        }
    }

    @Override
    public SpecialistChainExecutionRecord create(
        SpecialistChainExecutionRecord record
    ) {
        try {
            jdbc.update(
                """
                INSERT INTO ai_specialist_chain_execution (
                  execution_id, chain_name, chain_version,
                  chain_content_hash, manager_name, manager_version,
                  manager_content_hash, access_fingerprint,
                  idempotency_fingerprint, request_fingerprint,
                  protected_request, protected_checkpoint,
                  protected_result, status, failure_reason,
                  next_decision_index, deadline, created_at, updated_at,
                  completed_at, expires_at, lease_owner, lease_until,
                  attempt_count, version
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?
                )
                """,
                record.executionId(),
                record.chainId().name(),
                record.chainId().version(),
                record.chainContentHash(),
                record.managerSpecialistId().name(),
                record.managerSpecialistId().version(),
                record.managerContentHash(),
                record.accessFingerprint(),
                record.idempotencyFingerprint(),
                record.requestFingerprint(),
                record.protectedRequest(),
                record.protectedCheckpoint(),
                record.protectedResult(),
                record.status().name(),
                record.failureReason(),
                record.nextDecisionIndex(),
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
            throw new DuplicateChainExecutionException(
                "Duplicate chain execution ID or idempotency fingerprint",
                ex
            );
        }
    }

    @Override
    public Optional<SpecialistChainExecutionRecord> findById(
        String executionId
    ) {
        return queryOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE execution_id = ?",
            executionId
        );
    }

    @Override
    public Optional<SpecialistChainExecutionRecord>
        findByIdempotencyFingerprint(String fingerprint) {
        return queryOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE idempotency_fingerprint = ?",
            fingerprint
        );
    }

    @Override
    public boolean compareAndSet(
        SpecialistChainExecutionRecord expected,
        SpecialistChainExecutionRecord updated
    ) {
        validateTransition(expected, updated);
        return jdbc.update(
            """
            UPDATE ai_specialist_chain_execution
               SET protected_checkpoint = ?, protected_result = ?,
                   status = ?, failure_reason = ?, next_decision_index = ?,
                   updated_at = ?, completed_at = ?, expires_at = ?,
                   lease_owner = ?, lease_until = ?, attempt_count = ?,
                   version = ?
             WHERE execution_id = ? AND status = ? AND version = ?
               AND (lease_owner = ? OR (lease_owner IS NULL AND ? IS NULL))
            """,
            updated.protectedCheckpoint(),
            updated.protectedResult(),
            updated.status().name(),
            updated.failureReason(),
            updated.nextDecisionIndex(),
            timestamp(updated.updatedAt()),
            timestamp(updated.completedAt()),
            timestamp(updated.expiresAt()),
            updated.leaseOwner(),
            timestamp(updated.leaseUntil()),
            updated.attemptCount(),
            updated.version(),
            expected.executionId(),
            expected.status().name(),
            expected.version(),
            varchar(expected.leaseOwner()),
            varchar(expected.leaseOwner())
        ) == 1;
    }

    @Override
    public List<SpecialistChainExecutionRecord> findRecoverable(
        Instant now,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status = ? OR (status = ? AND lease_until <= ?)"
                + " ORDER BY updated_at ASC LIMIT ?",
            this::map,
            SpecialistChainExecutionStatus.QUEUED.name(),
            SpecialistChainExecutionStatus.RUNNING.name(),
            timestamp(now),
            positive(limit)
        );
    }

    @Override
    public List<SpecialistChainExecutionRecord>
        findTerminalCompletedBefore(Instant cutoff, int limit) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status NOT IN (?, ?) AND completed_at < ?"
                + " ORDER BY completed_at ASC LIMIT ?",
            this::map,
            SpecialistChainExecutionStatus.QUEUED.name(),
            SpecialistChainExecutionStatus.RUNNING.name(),
            timestamp(cutoff),
            positive(limit)
        );
    }

    @Override
    public long countActive() {
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + TABLE + " WHERE status IN (?, ?)",
            Long.class,
            SpecialistChainExecutionStatus.QUEUED.name(),
            SpecialistChainExecutionStatus.RUNNING.name()
        );
        return count == null ? 0 : count;
    }

    @Override
    public boolean delete(SpecialistChainExecutionRecord expected) {
        if (!expected.status().terminal()) {
            return false;
        }
        return jdbc.update(
            "DELETE FROM " + TABLE
                + " WHERE execution_id = ? AND status = ? AND version = ?",
            expected.executionId(),
            expected.status().name(),
            expected.version()
        ) == 1;
    }

    private Optional<SpecialistChainExecutionRecord> queryOne(
        String sql,
        Object value
    ) {
        if (value == null) {
            return Optional.empty();
        }
        return jdbc.query(sql, this::map, value).stream().findFirst();
    }

    private SpecialistChainExecutionRecord map(ResultSet row, int index)
        throws SQLException {
        return new SpecialistChainExecutionRecord(
            row.getString("execution_id"),
            SpecialistChainId.of(
                row.getString("chain_name"),
                row.getString("chain_version")
            ),
            row.getString("chain_content_hash"),
            SpecialistId.of(
                row.getString("manager_name"),
                row.getString("manager_version")
            ),
            row.getString("manager_content_hash"),
            row.getString("access_fingerprint"),
            row.getString("idempotency_fingerprint"),
            row.getString("request_fingerprint"),
            row.getString("protected_request"),
            row.getString("protected_checkpoint"),
            row.getString("protected_result"),
            SpecialistChainExecutionStatus.valueOf(row.getString("status")),
            row.getString("failure_reason"),
            row.getInt("next_decision_index"),
            instant(row, "deadline"),
            instant(row, "created_at"),
            instant(row, "updated_at"),
            instant(row, "completed_at"),
            instant(row, "expires_at"),
            row.getString("lease_owner"),
            instant(row, "lease_until"),
            row.getInt("attempt_count"),
            row.getLong("version")
        );
    }

    private void initializeSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_specialist_chain_execution (
              execution_id VARCHAR(120) PRIMARY KEY,
              chain_name VARCHAR(120) NOT NULL,
              chain_version VARCHAR(80) NOT NULL,
              chain_content_hash VARCHAR(64) NOT NULL,
              manager_name VARCHAR(120) NOT NULL,
              manager_version VARCHAR(80) NOT NULL,
              manager_content_hash VARCHAR(64) NOT NULL,
              access_fingerprint VARCHAR(64) NOT NULL,
              idempotency_fingerprint VARCHAR(64) NOT NULL UNIQUE,
              request_fingerprint VARCHAR(64) NOT NULL,
              protected_request TEXT NOT NULL,
              protected_checkpoint TEXT NOT NULL,
              protected_result TEXT NULL,
              status VARCHAR(40) NOT NULL,
              failure_reason VARCHAR(160) NULL,
              next_decision_index INTEGER NOT NULL,
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
            "CREATE INDEX IF NOT EXISTS idx_ai_chain_recovery ON "
                + TABLE + " (status, lease_until, updated_at)"
        );
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_chain_expiry ON "
                + TABLE + " (completed_at)"
        );
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_chain_access ON "
                + TABLE + " (access_fingerprint)"
        );
    }

    private void validateTransition(
        SpecialistChainExecutionRecord expected,
        SpecialistChainExecutionRecord updated
    ) {
        if (!expected.executionId().equals(updated.executionId())
            || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                "Chain transition must preserve ID and increment version"
            );
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private SqlParameterValue varchar(String value) {
        return new SqlParameterValue(Types.VARCHAR, value);
    }

    private Instant instant(ResultSet row, String column)
        throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private int positive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return limit;
    }
}
