package ai.fabric.execution.review.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.review.ReviewSourceType;
import ai.fabric.execution.review.ReviewTaskStatus;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.dispatch.ReviewDispatchStatus;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.review.policy.ReviewType;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcReviewRepositoryTest {

    private static final Instant NOW =
        Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void taskAndDispatchSurviveRestartAndUseOptimisticTransitions() {
        JdbcDataSource dataSource = dataSource();
        JdbcReviewTaskRepository tasks = new JdbcReviewTaskRepository(
            dataSource,
            new ObjectMapper(),
            true
        );
        JdbcReviewDispatchRepository dispatches =
            new JdbcReviewDispatchRepository(dataSource, true);
        ReviewTaskRecord waiting = waitingTask();
        tasks.create(waiting);
        ReviewDispatchRecord pending = pendingDispatch();
        dispatches.create(pending);

        JdbcReviewTaskRepository restarted =
            new JdbcReviewTaskRepository(
                dataSource,
                new ObjectMapper(),
                false
            );
        ReviewTaskRecord restored = restarted
            .findById(waiting.taskId())
            .orElseThrow();
        assertThat(restored).isEqualTo(waiting);
        ReviewTaskRecord deciding = restored.claim(
            ReviewDecisionType.APPROVE,
            hash("decision"),
            hash("reviewer"),
            "v1.protected-decision",
            "worker-1",
            NOW.plusSeconds(1),
            NOW.plusSeconds(31)
        );
        assertThat(restarted.compareAndSet(restored, deciding)).isTrue();
        assertThat(restarted.compareAndSet(restored, deciding)).isFalse();

        ReviewDispatchRecord accepted = pending.completed(
            ReviewDispatchStatus.ACCEPTED,
            "inbox-1",
            null,
            NOW.plusSeconds(1)
        );
        assertThat(dispatches.compareAndSet(pending, accepted)).isTrue();
        assertThat(dispatches.findByTaskId(waiting.taskId()))
            .containsExactly(accepted);
    }

    @Test
    void databaseColumnsDoNotContainRawSensitiveReviewMaterial() {
        JdbcDataSource dataSource = dataSource();
        JdbcReviewTaskRepository tasks = new JdbcReviewTaskRepository(
            dataSource,
            new ObjectMapper(),
            true
        );
        tasks.create(waitingTask());

        String row = new JdbcTemplate(dataSource).queryForObject(
            """
            SELECT CONCAT(
              task_id, policy_name, policy_version, policy_content_hash,
              review_type, source_type, source_fingerprint,
              initiator_fingerprint, subject_fingerprint, tenant_fingerprint,
              deployment_fingerprint, idempotency_fingerprint,
              request_fingerprint,
              protected_source, protected_presentation, allowed_decisions,
              status
            )
            FROM ai_review_task
            """,
            String.class
        );

        assertThat(row)
            .doesNotContain(
                "receipt-secret",
                "principal@example.com",
                "Approve the private refund"
            );
    }

    @Test
    void scopedIdempotencyAndDispatchKeysAreUnique() {
        JdbcDataSource dataSource = dataSource();
        JdbcReviewTaskRepository tasks = new JdbcReviewTaskRepository(
            dataSource,
            new ObjectMapper(),
            true
        );
        JdbcReviewDispatchRepository dispatches =
            new JdbcReviewDispatchRepository(dataSource, true);
        ReviewTaskRecord task = waitingTask();
        tasks.create(task);
        dispatches.create(pendingDispatch());

        assertThatThrownBy(() -> tasks.create(task))
            .isInstanceOf(
                ReviewTaskRepository.DuplicateTaskException.class
            );
        assertThatThrownBy(() -> dispatches.create(pendingDispatch()))
            .isInstanceOf(
                ReviewDispatchRepository.DuplicateDispatchException.class
            );
    }

    @Test
    void inMemoryDispatchRepositoryMatchesJdbcIdempotencyAndCleanup() {
        InMemoryReviewDispatchRepository dispatches =
            new InMemoryReviewDispatchRepository();
        ReviewDispatchRecord first = pendingDispatch();
        ReviewDispatchRecord sameKey = new ReviewDispatchRecord(
            "review-dispatch-2",
            "review-task-2",
            first.dispatcherId(),
            first.attemptNumber(),
            first.idempotencyKey(),
            first.status(),
            null,
            null,
            NOW,
            null,
            0
        );
        dispatches.create(first);

        assertThatThrownBy(() -> dispatches.create(sameKey))
            .isInstanceOf(
                ReviewDispatchRepository.DuplicateDispatchException.class
            );
        assertThat(dispatches.deleteByTaskId(first.taskId())).isEqualTo(1);
        assertThat(dispatches.findByTaskId(first.taskId())).isEmpty();
    }

    private ReviewTaskRecord waitingTask() {
        return new ReviewTaskRecord(
            "review-task-1",
            ReviewPolicyId.of("support-credit-review", "1"),
            hash("policy"),
            ReviewType.OPERATIONAL_REVIEW,
            ReviewSourceType.ACTION_PROPOSAL,
            hash("source"),
            hash("initiator"),
            hash("subject"),
            hash("tenant"),
            hash("deployment"),
            hash("idempotency"),
            hash("request"),
            "v1.encrypted-source",
            "v1.encrypted-presentation",
            Set.of(
                ReviewDecisionType.APPROVE,
                ReviewDecisionType.REJECT
            ),
            ReviewTaskStatus.WAITING_FOR_REVIEW,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW.plusSeconds(300),
            NOW,
            null,
            null,
            null,
            0,
            0
        );
    }

    private ReviewDispatchRecord pendingDispatch() {
        return new ReviewDispatchRecord(
            "review-dispatch-1",
            "review-task-1",
            "local-review-inbox@1",
            1,
            "review-task-1:local-review-inbox@1:1",
            ReviewDispatchStatus.PENDING,
            null,
            null,
            NOW,
            null,
            0
        );
    }

    private String hash(String value) {
        return CanonicalJsonSupport.sha256(value);
    }

    private JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:review-" + java.util.UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
