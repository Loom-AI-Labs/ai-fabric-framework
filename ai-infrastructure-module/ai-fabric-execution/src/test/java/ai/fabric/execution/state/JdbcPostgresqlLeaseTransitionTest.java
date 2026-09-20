package ai.fabric.execution.state;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.review.ReviewSourceType;
import ai.fabric.execution.review.ReviewTaskStatus;
import ai.fabric.execution.review.decision.ReviewDecisionType;
import ai.fabric.execution.review.persistence.JdbcReviewTaskRepository;
import ai.fabric.execution.review.persistence.ReviewTaskRecord;
import ai.fabric.execution.review.policy.ReviewPolicyId;
import ai.fabric.execution.review.policy.ReviewType;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class JdbcPostgresqlLeaseTransitionTest {

    private static final Instant NOW =
        Instant.parse("2026-09-20T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void durableExecutionClaimsAnUnleasedPostgresqlRow() {
        JdbcDurableExecutionRepository repository =
            new JdbcDurableExecutionRepository(dataSource(), true);
        DurableExecutionRecord queued = DurableExecutionRecord.queued(
            "postgres-execution",
            SpecialistId.of("event-analyst", "1"),
            hash("specialist"),
            hash("access"),
            hash("idempotency"),
            hash("request"),
            "v1.protected-request",
            NOW.plusSeconds(300),
            NOW,
            Duration.ofDays(30)
        );
        repository.create(queued);

        DurableExecutionRecord claimed = queued.claimed(
            "postgres-worker",
            NOW.plusSeconds(1),
            NOW.plusSeconds(31)
        );

        assertThat(repository.compareAndSet(queued, claimed)).isTrue();
        DurableExecutionRecord succeeded = claimed.completed(
            ExecutionHandleStatus.SUCCEEDED,
            "v1.protected-result",
            null,
            NOW.plusSeconds(2),
            Duration.ofDays(30)
        );
        assertThat(repository.compareAndSet(claimed, succeeded)).isTrue();
        assertThat(repository.findById(queued.invocationId()))
            .contains(succeeded);
    }

    @Test
    void reviewDecisionClaimsAnUnleasedPostgresqlRow() {
        JdbcReviewTaskRepository repository = new JdbcReviewTaskRepository(
            dataSource(),
            new ObjectMapper(),
            true
        );
        ReviewTaskRecord waiting = new ReviewTaskRecord(
            "postgres-review-task",
            ReviewPolicyId.of("support-review", "1"),
            hash("policy"),
            ReviewType.OPERATIONAL_REVIEW,
            ReviewSourceType.ACTION_PROPOSAL,
            hash("source"),
            hash("initiator"),
            hash("subject"),
            hash("tenant"),
            hash("deployment"),
            hash("review-idempotency"),
            hash("review-request"),
            "v1.protected-source",
            "v1.protected-presentation",
            Set.of(ReviewDecisionType.APPROVE, ReviewDecisionType.REJECT),
            ReviewTaskStatus.WAITING_FOR_REVIEW,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW.plus(Duration.ofDays(30)),
            NOW,
            null,
            null,
            null,
            0,
            0
        );
        repository.create(waiting);

        ReviewTaskRecord deciding = waiting.claim(
            ReviewDecisionType.APPROVE,
            hash("decision"),
            hash("reviewer"),
            "v1.protected-decision",
            "postgres-review-worker",
            NOW.plusSeconds(1),
            NOW.plusSeconds(31)
        );

        assertThat(repository.compareAndSet(waiting, deciding)).isTrue();
        assertThat(repository.findById(waiting.taskId())).contains(deciding);
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private String hash(String value) {
        return CanonicalJsonSupport.sha256(value);
    }
}
