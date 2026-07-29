package ai.fabric.execution.review.persistence;

import ai.fabric.execution.review.dispatch.ReviewDispatchStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcReviewDispatchRepository
    implements ReviewDispatchRepository {

    private static final String TABLE = "ai_review_dispatch";
    private static final String SELECT_COLUMNS = """
        dispatch_id, task_id, dispatcher_id, attempt_number, idempotency_key,
        status, external_reference, failure_reason, created_at, completed_at,
        version
        """;

    private final JdbcTemplate jdbc;

    public JdbcReviewDispatchRepository(
        DataSource dataSource,
        boolean initializeSchema
    ) {
        this.jdbc = new JdbcTemplate(dataSource);
        if (initializeSchema) {
            initializeSchema();
        }
    }

    @Override
    public ReviewDispatchRecord create(ReviewDispatchRecord dispatch) {
        try {
            jdbc.update(
                """
                INSERT INTO ai_review_dispatch (
                  dispatch_id, task_id, dispatcher_id, attempt_number,
                  idempotency_key, status, external_reference, failure_reason,
                  created_at, completed_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                dispatch.dispatchId(),
                dispatch.taskId(),
                dispatch.dispatcherId(),
                dispatch.attemptNumber(),
                dispatch.idempotencyKey(),
                dispatch.status().name(),
                dispatch.externalReference(),
                dispatch.failureReason(),
                timestamp(dispatch.createdAt()),
                timestamp(dispatch.completedAt()),
                dispatch.version()
            );
            return dispatch;
        } catch (DuplicateKeyException ex) {
            throw new DuplicateDispatchException(
                "Duplicate review dispatch ID or idempotency key",
                ex
            );
        }
    }

    @Override
    public Optional<ReviewDispatchRecord> findById(String dispatchId) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE dispatch_id = ?",
            this::map,
            dispatchId
        ).stream().findFirst();
    }

    @Override
    public List<ReviewDispatchRecord> findByTaskId(String taskId) {
        return jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM " + TABLE
                + " WHERE task_id = ? ORDER BY attempt_number ASC",
            this::map,
            taskId
        );
    }

    @Override
    public boolean compareAndSet(
        ReviewDispatchRecord expected,
        ReviewDispatchRecord updated
    ) {
        if (!expected.dispatchId().equals(updated.dispatchId())
            || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                "Dispatch transition must preserve ID and increment version"
            );
        }
        return jdbc.update(
            """
            UPDATE ai_review_dispatch
               SET status = ?,
                   external_reference = ?,
                   failure_reason = ?,
                   completed_at = ?,
                   version = ?
             WHERE dispatch_id = ?
               AND status = ?
               AND version = ?
            """,
            updated.status().name(),
            updated.externalReference(),
            updated.failureReason(),
            timestamp(updated.completedAt()),
            updated.version(),
            expected.dispatchId(),
            expected.status().name(),
            expected.version()
        ) == 1;
    }

    @Override
    public int deleteByTaskId(String taskId) {
        return jdbc.update(
            "DELETE FROM " + TABLE + " WHERE task_id = ?",
            taskId
        );
    }

    private ReviewDispatchRecord map(ResultSet resultSet, int row)
        throws SQLException {
        return new ReviewDispatchRecord(
            resultSet.getString("dispatch_id"),
            resultSet.getString("task_id"),
            resultSet.getString("dispatcher_id"),
            resultSet.getInt("attempt_number"),
            resultSet.getString("idempotency_key"),
            ReviewDispatchStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("external_reference"),
            resultSet.getString("failure_reason"),
            instant(resultSet, "created_at"),
            instant(resultSet, "completed_at"),
            resultSet.getLong("version")
        );
    }

    private void initializeSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ai_review_dispatch (
              dispatch_id VARCHAR(120) PRIMARY KEY,
              task_id VARCHAR(120) NOT NULL,
              dispatcher_id VARCHAR(160) NOT NULL,
              attempt_number INTEGER NOT NULL,
              idempotency_key VARCHAR(200) NOT NULL UNIQUE,
              status VARCHAR(40) NOT NULL,
              external_reference VARCHAR(240) NULL,
              failure_reason VARCHAR(160) NULL,
              created_at TIMESTAMP NOT NULL,
              completed_at TIMESTAMP NULL,
              version BIGINT NOT NULL
            )
            """);
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_ai_review_dispatch_task"
                + " ON " + TABLE + " (task_id, attempt_number)"
        );
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet resultSet, String column)
        throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
