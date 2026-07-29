package ai.fabric.execution.action;

import ai.fabric.execution.specialist.SpecialistId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * JDBC receipt store with optimistic compare-and-set transitions.
 */
public final class JdbcActionProposalReceiptRepository
    implements ActionProposalReceiptRepository {

    private static final String TABLE = "ai_action_proposal_receipt";
    private static final String SELECT_COLUMNS = """
        receipt_id, invocation_id, specialist_name, specialist_version,
        specialist_content_hash, effective_profile_hash,
        principal_fingerprint, subject_type,
        subject_fingerprint, tenant_fingerprint, deployment_fingerprint,
        action_name, protected_parameters, parameter_hash,
        parameter_schema_hash, confirmation_message, idempotency_key,
        evidence_hashes, status, created_at, expires_at, confirmed_at,
        execution_started_at, executed_at, terminal_at, protected_outcome,
        failure_reason, updated_at, version
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcActionProposalReceiptRepository(
        DataSource dataSource,
        ObjectMapper objectMapper,
        boolean initializeSchema
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper.copy();
        if (initializeSchema) {
            initializeSchema();
        }
    }

    @Override
    public ActionProposalReceipt create(ActionProposalReceipt receipt) {
        String sql = """
            INSERT INTO ai_action_proposal_receipt (
              receipt_id, invocation_id, specialist_name, specialist_version,
              specialist_content_hash, effective_profile_hash,
              principal_fingerprint, subject_type,
              subject_fingerprint, tenant_fingerprint, deployment_fingerprint,
              action_name, protected_parameters, parameter_hash,
              parameter_schema_hash, confirmation_message, idempotency_key,
              evidence_hashes, status, created_at, expires_at, confirmed_at,
              execution_started_at, executed_at, terminal_at, protected_outcome,
              failure_reason, updated_at, version
            ) VALUES (
              ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
              ?, ?, ?, ?, ?, ?, ?, ?
            )
            """;
        try {
            jdbc.update(
                sql,
                receipt.receiptId(),
                receipt.invocationId(),
                receipt.specialistId().name(),
                receipt.specialistId().version(),
                receipt.specialistContentHash(),
                receipt.effectiveProfileHash(),
                receipt.principalFingerprint(),
                receipt.subjectType(),
                receipt.subjectFingerprint(),
                receipt.tenantFingerprint(),
                receipt.deploymentFingerprint(),
                receipt.actionName(),
                receipt.protectedParameters(),
                receipt.parameterHash(),
                receipt.parameterSchemaHash(),
                receipt.confirmationMessage(),
                receipt.idempotencyKey(),
                writeHashes(receipt.evidenceHashes()),
                receipt.status().name(),
                timestamp(receipt.createdAt()),
                timestamp(receipt.expiresAt()),
                timestamp(receipt.confirmedAt()),
                timestamp(receipt.executionStartedAt()),
                timestamp(receipt.executedAt()),
                timestamp(receipt.terminalAt()),
                receipt.protectedOutcome(),
                receipt.failureReason(),
                timestamp(receipt.updatedAt()),
                receipt.version()
            );
            return receipt;
        } catch (DuplicateKeyException ex) {
            throw new DuplicateReceiptException(
                "Duplicate receipt ID or idempotency key",
                ex
            );
        }
    }

    @Override
    public Optional<ActionProposalReceipt> findById(String receiptId) {
        return queryOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE receipt_id = ?",
            receiptId
        );
    }

    @Override
    public Optional<ActionProposalReceipt> findByIdempotencyKey(
        String idempotencyKey
    ) {
        return queryOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE idempotency_key = ?",
            idempotencyKey
        );
    }

    @Override
    public boolean compareAndSet(
        ActionProposalReceipt expected,
        ActionProposalReceipt updated
    ) {
        if (!expected.receiptId().equals(updated.receiptId())
            || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                "Receipt transition must preserve ID and increment version"
            );
        }
        int rows = jdbc.update(
            """
            UPDATE ai_action_proposal_receipt
               SET status = ?,
                   confirmed_at = ?,
                   execution_started_at = ?,
                   executed_at = ?,
                   terminal_at = ?,
                   protected_outcome = ?,
                   failure_reason = ?,
                   updated_at = ?,
                   version = ?
             WHERE receipt_id = ?
               AND status = ?
               AND version = ?
            """,
            updated.status().name(),
            timestamp(updated.confirmedAt()),
            timestamp(updated.executionStartedAt()),
            timestamp(updated.executedAt()),
            timestamp(updated.terminalAt()),
            updated.protectedOutcome(),
            updated.failureReason(),
            timestamp(updated.updatedAt()),
            updated.version(),
            expected.receiptId(),
            expected.status().name(),
            expected.version()
        );
        return rows == 1;
    }

    @Override
    public List<ActionProposalReceipt> findExpiredConfirmable(
        Instant now,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status IN (?, ?) AND expires_at <= ?"
                + " ORDER BY expires_at ASC LIMIT ?",
            this::map,
            ActionProposalReceiptStatus.PROPOSED.name(),
            ActionProposalReceiptStatus.CONFIRMED.name(),
            timestamp(now),
            positiveLimit(limit)
        );
    }

    @Override
    public List<ActionProposalReceipt> findUpdatedBefore(
        ActionProposalReceiptStatus status,
        Instant cutoff,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status = ? AND updated_at < ?"
                + " ORDER BY updated_at ASC LIMIT ?",
            this::map,
            status.name(),
            timestamp(cutoff),
            positiveLimit(limit)
        );
    }

    @Override
    public List<ActionProposalReceipt> findRetainableTerminalBefore(
        Instant cutoff,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status IN (?, ?, ?, ?) AND terminal_at < ?"
                + " ORDER BY terminal_at ASC LIMIT ?",
            this::map,
            ActionProposalReceiptStatus.SUCCEEDED.name(),
            ActionProposalReceiptStatus.FAILED.name(),
            ActionProposalReceiptStatus.REJECTED.name(),
            ActionProposalReceiptStatus.EXPIRED.name(),
            timestamp(cutoff),
            positiveLimit(limit)
        );
    }

    @Override
    public boolean delete(ActionProposalReceipt expected) {
        return jdbc.update(
            "DELETE FROM " + TABLE
                + " WHERE receipt_id = ? AND status = ? AND version = ?",
            expected.receiptId(),
            expected.status().name(),
            expected.version()
        ) == 1;
    }

    private Optional<ActionProposalReceipt> queryOne(
        String sql,
        Object value
    ) {
        List<ActionProposalReceipt> rows = jdbc.query(
            sql,
            this::map,
            value
        );
        return rows.stream().findFirst();
    }

    private ActionProposalReceipt map(ResultSet resultSet, int row)
        throws SQLException {
        return new ActionProposalReceipt(
            resultSet.getString("receipt_id"),
            resultSet.getString("invocation_id"),
            SpecialistId.of(
                resultSet.getString("specialist_name"),
                resultSet.getString("specialist_version")
            ),
            resultSet.getString("specialist_content_hash"),
            resultSet.getString("effective_profile_hash"),
            resultSet.getString("principal_fingerprint"),
            resultSet.getString("subject_type"),
            resultSet.getString("subject_fingerprint"),
            resultSet.getString("tenant_fingerprint"),
            resultSet.getString("deployment_fingerprint"),
            resultSet.getString("action_name"),
            resultSet.getString("protected_parameters"),
            resultSet.getString("parameter_hash"),
            resultSet.getString("parameter_schema_hash"),
            resultSet.getString("confirmation_message"),
            resultSet.getString("idempotency_key"),
            readHashes(resultSet.getString("evidence_hashes")),
            ActionProposalReceiptStatus.valueOf(resultSet.getString("status")),
            instant(resultSet, "created_at"),
            instant(resultSet, "expires_at"),
            instant(resultSet, "confirmed_at"),
            instant(resultSet, "execution_started_at"),
            instant(resultSet, "executed_at"),
            instant(resultSet, "terminal_at"),
            resultSet.getString("protected_outcome"),
            resultSet.getString("failure_reason"),
            instant(resultSet, "updated_at"),
            resultSet.getLong("version")
        );
    }

    private void initializeSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_action_proposal_receipt (
              receipt_id VARCHAR(120) PRIMARY KEY,
              invocation_id VARCHAR(120) NOT NULL,
              specialist_name VARCHAR(120) NOT NULL,
              specialist_version VARCHAR(80) NOT NULL,
              specialist_content_hash VARCHAR(128) NOT NULL,
              effective_profile_hash VARCHAR(128) NOT NULL,
              principal_fingerprint VARCHAR(128) NOT NULL,
              subject_type VARCHAR(80) NOT NULL,
              subject_fingerprint VARCHAR(128) NOT NULL,
              tenant_fingerprint VARCHAR(128) NOT NULL,
              deployment_fingerprint VARCHAR(128) NOT NULL,
              action_name VARCHAR(160) NOT NULL,
              protected_parameters TEXT NOT NULL,
              parameter_hash VARCHAR(128) NOT NULL,
              parameter_schema_hash VARCHAR(128) NOT NULL,
              confirmation_message VARCHAR(1000) NOT NULL,
              idempotency_key VARCHAR(200) NOT NULL UNIQUE,
              evidence_hashes TEXT NOT NULL,
              status VARCHAR(40) NOT NULL,
              created_at TIMESTAMP NOT NULL,
              expires_at TIMESTAMP NOT NULL,
              confirmed_at TIMESTAMP NULL,
              execution_started_at TIMESTAMP NULL,
              executed_at TIMESTAMP NULL,
              terminal_at TIMESTAMP NULL,
              protected_outcome TEXT NULL,
              failure_reason VARCHAR(160) NULL,
              updated_at TIMESTAMP NOT NULL,
              version BIGINT NOT NULL
            )
            """);
        jdbc.execute(
            "ALTER TABLE " + TABLE
                + " ADD COLUMN IF NOT EXISTS specialist_content_hash VARCHAR(128)"
        );
        jdbc.update(
            "UPDATE " + TABLE
                + " SET specialist_content_hash = ?"
                + " WHERE specialist_content_hash IS NULL",
            "legacy-unpinned"
        );
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_action_receipt_status"
                + " ON " + TABLE + " (status)"
        );
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_action_receipt_expiry"
                + " ON " + TABLE + " (expires_at)"
        );
    }

    private String writeHashes(List<String> hashes) {
        try {
            return objectMapper.writeValueAsString(hashes);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                "Could not serialize receipt evidence hashes",
                ex
            );
        }
    }

    private List<String> readHashes(String json) {
        try {
            return objectMapper.readValue(
                json,
                new TypeReference<List<String>>() {}
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                "Stored receipt evidence hashes are invalid",
                ex
            );
        }
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
