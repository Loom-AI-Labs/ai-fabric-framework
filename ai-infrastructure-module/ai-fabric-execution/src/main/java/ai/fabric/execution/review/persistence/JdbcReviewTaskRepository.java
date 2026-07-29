package ai.fabric.execution.review.persistence;

import ai.fabric.execution.review.ReviewSourceType;
import ai.fabric.execution.review.ReviewTaskStatus;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.review.policy.ReviewType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC review-task state with optimistic transitions and no raw sensitive
 * source, presentation, decision, or identity columns.
 */
public final class JdbcReviewTaskRepository
    implements ReviewTaskRepository {

    private static final String TABLE = "ai_review_task";
    private static final String SELECT_COLUMNS = """
        task_id, policy_name, policy_version, policy_content_hash,
        review_type, source_type, source_fingerprint,
        initiator_fingerprint, subject_fingerprint, tenant_fingerprint,
        deployment_fingerprint, idempotency_fingerprint,
        request_fingerprint,
        protected_source, protected_presentation, allowed_decisions,
        status, decision_type, decision_fingerprint, reviewer_fingerprint,
        protected_decision, protected_result, failure_reason,
        successor_task_id, created_at, expires_at, updated_at, terminal_at,
        lease_owner, lease_until, attempt_count, version
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcReviewTaskRepository(
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
    public ReviewTaskRecord create(ReviewTaskRecord task) {
        try {
            jdbc.update(
                """
                INSERT INTO ai_review_task (
                  task_id, policy_name, policy_version, policy_content_hash,
                  review_type, source_type, source_fingerprint,
                  initiator_fingerprint, subject_fingerprint,
                  tenant_fingerprint, deployment_fingerprint,
                  idempotency_fingerprint, request_fingerprint,
                  protected_source,
                  protected_presentation, allowed_decisions, status,
                  decision_type, decision_fingerprint, reviewer_fingerprint,
                  protected_decision, protected_result, failure_reason,
                  successor_task_id, created_at, expires_at, updated_at,
                  terminal_at, lease_owner, lease_until, attempt_count, version
                ) VALUES (
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                task.taskId(),
                task.policyId().name(),
                task.policyId().version(),
                task.policyContentHash(),
                task.reviewType().name(),
                task.sourceType().name(),
                task.sourceFingerprint(),
                task.initiatorFingerprint(),
                task.subjectFingerprint(),
                task.tenantFingerprint(),
                task.deploymentFingerprint(),
                task.idempotencyFingerprint(),
                task.requestFingerprint(),
                task.protectedSource(),
                task.protectedPresentation(),
                writeDecisions(task.allowedDecisions()),
                task.status().name(),
                name(task.decisionType()),
                task.decisionFingerprint(),
                task.reviewerFingerprint(),
                task.protectedDecision(),
                task.protectedResult(),
                task.failureReason(),
                task.successorTaskId(),
                timestamp(task.createdAt()),
                timestamp(task.expiresAt()),
                timestamp(task.updatedAt()),
                timestamp(task.terminalAt()),
                task.leaseOwner(),
                timestamp(task.leaseUntil()),
                task.attemptCount(),
                task.version()
            );
            return task;
        } catch (DuplicateKeyException ex) {
            throw new DuplicateTaskException(
                "Duplicate review task ID or scoped idempotency fingerprint",
                ex
            );
        }
    }

    @Override
    public Optional<ReviewTaskRecord> findById(String taskId) {
        return queryOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE task_id = ?",
            taskId
        );
    }

    @Override
    public Optional<ReviewTaskRecord> findByIdempotencyFingerprint(
        String fingerprint
    ) {
        return queryOne(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE idempotency_fingerprint = ?",
            fingerprint
        );
    }

    @Override
    public boolean compareAndSet(
        ReviewTaskRecord expected,
        ReviewTaskRecord updated
    ) {
        if (!expected.taskId().equals(updated.taskId())
            || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                "Task transition must preserve ID and increment version"
            );
        }
        int rows = jdbc.update(
            """
            UPDATE ai_review_task
               SET status = ?,
                   decision_type = ?,
                   decision_fingerprint = ?,
                   reviewer_fingerprint = ?,
                   protected_decision = ?,
                   protected_result = ?,
                   failure_reason = ?,
                   successor_task_id = ?,
                   updated_at = ?,
                   terminal_at = ?,
                   lease_owner = ?,
                   lease_until = ?,
                   attempt_count = ?,
                   version = ?
             WHERE task_id = ?
               AND status = ?
               AND version = ?
               AND (
                 lease_owner = ?
                 OR (lease_owner IS NULL AND ? IS NULL)
               )
            """,
            updated.status().name(),
            name(updated.decisionType()),
            updated.decisionFingerprint(),
            updated.reviewerFingerprint(),
            updated.protectedDecision(),
            updated.protectedResult(),
            updated.failureReason(),
            updated.successorTaskId(),
            timestamp(updated.updatedAt()),
            timestamp(updated.terminalAt()),
            updated.leaseOwner(),
            timestamp(updated.leaseUntil()),
            updated.attemptCount(),
            updated.version(),
            expected.taskId(),
            expected.status().name(),
            expected.version(),
            expected.leaseOwner(),
            expected.leaseOwner()
        );
        return rows == 1;
    }

    @Override
    public List<ReviewTaskRecord> findByTenantAndStatus(
        String tenantFingerprint,
        ReviewTaskStatus status,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE tenant_fingerprint = ? AND status = ?"
                + " ORDER BY created_at ASC LIMIT ?",
            this::map,
            tenantFingerprint,
            status.name(),
            positive(limit)
        );
    }

    @Override
    public List<ReviewTaskRecord> findByStatus(
        ReviewTaskStatus status,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status = ?"
                + " ORDER BY updated_at ASC LIMIT ?",
            this::map,
            status.name(),
            positive(limit)
        );
    }

    @Override
    public List<ReviewTaskRecord> findRecoverable(
        Instant now,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status = ? AND lease_until <= ?"
                + " ORDER BY updated_at ASC LIMIT ?",
            this::map,
            ReviewTaskStatus.DECIDING.name(),
            timestamp(now),
            positive(limit)
        );
    }

    @Override
    public List<ReviewTaskRecord> findExpiredWaiting(
        Instant now,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status IN (?, ?) AND expires_at <= ?"
                + " ORDER BY expires_at ASC LIMIT ?",
            this::map,
            ReviewTaskStatus.WAITING_FOR_REVIEW.name(),
            ReviewTaskStatus.WAITING_FOR_INFORMATION.name(),
            timestamp(now),
            positive(limit)
        );
    }

    @Override
    public List<ReviewTaskRecord> findTerminalBefore(
        Instant cutoff,
        int limit
    ) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE status IN (?, ?, ?, ?, ?, ?)"
                + " AND terminal_at < ?"
                + " ORDER BY terminal_at ASC LIMIT ?",
            this::map,
            ReviewTaskStatus.APPROVED.name(),
            ReviewTaskStatus.REJECTED.name(),
            ReviewTaskStatus.CORRECTED.name(),
            ReviewTaskStatus.ESCALATED.name(),
            ReviewTaskStatus.EXPIRED.name(),
            ReviewTaskStatus.FAILED.name(),
            timestamp(cutoff),
            positive(limit)
        );
    }

    @Override
    public boolean delete(ReviewTaskRecord expected) {
        return jdbc.update(
            "DELETE FROM " + TABLE
                + " WHERE task_id = ? AND status = ? AND version = ?",
            expected.taskId(),
            expected.status().name(),
            expected.version()
        ) == 1;
    }

    private Optional<ReviewTaskRecord> queryOne(
        String sql,
        Object value
    ) {
        return jdbc.query(sql, this::map, value).stream().findFirst();
    }

    private ReviewTaskRecord map(ResultSet resultSet, int row)
        throws SQLException {
        return new ReviewTaskRecord(
            resultSet.getString("task_id"),
            ReviewPolicyId.of(
                resultSet.getString("policy_name"),
                resultSet.getString("policy_version")
            ),
            resultSet.getString("policy_content_hash"),
            ReviewType.valueOf(resultSet.getString("review_type")),
            ReviewSourceType.valueOf(resultSet.getString("source_type")),
            resultSet.getString("source_fingerprint"),
            resultSet.getString("initiator_fingerprint"),
            resultSet.getString("subject_fingerprint"),
            resultSet.getString("tenant_fingerprint"),
            resultSet.getString("deployment_fingerprint"),
            resultSet.getString("idempotency_fingerprint"),
            resultSet.getString("request_fingerprint"),
            resultSet.getString("protected_source"),
            resultSet.getString("protected_presentation"),
            readDecisions(resultSet.getString("allowed_decisions")),
            ReviewTaskStatus.valueOf(resultSet.getString("status")),
            enumValue(
                ReviewDecisionType.class,
                resultSet.getString("decision_type")
            ),
            resultSet.getString("decision_fingerprint"),
            resultSet.getString("reviewer_fingerprint"),
            resultSet.getString("protected_decision"),
            resultSet.getString("protected_result"),
            resultSet.getString("failure_reason"),
            resultSet.getString("successor_task_id"),
            instant(resultSet, "created_at"),
            instant(resultSet, "expires_at"),
            instant(resultSet, "updated_at"),
            instant(resultSet, "terminal_at"),
            resultSet.getString("lease_owner"),
            instant(resultSet, "lease_until"),
            resultSet.getInt("attempt_count"),
            resultSet.getLong("version")
        );
    }

    private void initializeSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_review_task (
              task_id VARCHAR(120) PRIMARY KEY,
              policy_name VARCHAR(120) NOT NULL,
              policy_version VARCHAR(120) NOT NULL,
              policy_content_hash VARCHAR(64) NOT NULL,
              review_type VARCHAR(40) NOT NULL,
              source_type VARCHAR(40) NOT NULL,
              source_fingerprint VARCHAR(64) NOT NULL,
              initiator_fingerprint VARCHAR(64) NOT NULL,
              subject_fingerprint VARCHAR(64) NOT NULL,
              tenant_fingerprint VARCHAR(64) NOT NULL,
              deployment_fingerprint VARCHAR(64) NOT NULL,
              idempotency_fingerprint VARCHAR(64) NOT NULL UNIQUE,
              request_fingerprint VARCHAR(64) NOT NULL,
              protected_source TEXT NOT NULL,
              protected_presentation TEXT NOT NULL,
              allowed_decisions TEXT NOT NULL,
              status VARCHAR(40) NOT NULL,
              decision_type VARCHAR(40) NULL,
              decision_fingerprint VARCHAR(64) NULL,
              reviewer_fingerprint VARCHAR(64) NULL,
              protected_decision TEXT NULL,
              protected_result TEXT NULL,
              failure_reason VARCHAR(160) NULL,
              successor_task_id VARCHAR(120) NULL,
              created_at TIMESTAMP NOT NULL,
              expires_at TIMESTAMP NOT NULL,
              updated_at TIMESTAMP NOT NULL,
              terminal_at TIMESTAMP NULL,
              lease_owner VARCHAR(160) NULL,
              lease_until TIMESTAMP NULL,
              attempt_count INTEGER NOT NULL,
              version BIGINT NOT NULL
            )
            """);
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_review_inbox"
                + " ON " + TABLE
                + " (tenant_fingerprint, status, created_at)"
        );
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_review_recovery"
                + " ON " + TABLE + " (status, lease_until, updated_at)"
        );
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_review_expiry"
                + " ON " + TABLE + " (status, expires_at)"
        );
    }

    private String writeDecisions(Set<ReviewDecisionType> decisions) {
        try {
            return objectMapper.writeValueAsString(
                decisions.stream().map(Enum::name).sorted().toList()
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                "Could not serialize review decisions",
                ex
            );
        }
    }

    private Set<ReviewDecisionType> readDecisions(String json) {
        try {
            return objectMapper.readValue(
                json,
                new TypeReference<List<String>>() {}
            ).stream()
                .map(ReviewDecisionType::valueOf)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                "Stored review decisions are invalid",
                ex
            );
        }
    }

    private <T extends Enum<T>> T enumValue(
        Class<T> type,
        String value
    ) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet resultSet, String column)
        throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private int positive(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return value;
    }
}
