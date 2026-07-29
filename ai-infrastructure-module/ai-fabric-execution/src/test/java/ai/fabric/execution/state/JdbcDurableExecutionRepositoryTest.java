package ai.fabric.execution.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import java.time.Duration;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcDurableExecutionRepositoryTest {

    private static final Instant NOW =
        Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void queuedExecutionSurvivesRepositoryRestartAndTransitionsWithCas() {
        JdbcDataSource dataSource = dataSource();
        JdbcDurableExecutionRepository first =
            new JdbcDurableExecutionRepository(dataSource, true);
        DurableExecutionRecord queued = queued("exec-restart", "a");
        first.create(queued);

        JdbcDurableExecutionRepository restarted =
            new JdbcDurableExecutionRepository(dataSource, false);
        DurableExecutionRecord restored = restarted
            .findById(queued.invocationId())
            .orElseThrow();

        assertThat(restored).isEqualTo(queued);
        DurableExecutionRecord running = restored.claimed(
            "worker-1",
            NOW.plusSeconds(1),
            NOW.plusSeconds(31)
        );
        assertThat(restarted.compareAndSet(restored, running)).isTrue();
        assertThat(restarted.compareAndSet(restored, running)).isFalse();

        DurableExecutionRecord succeeded = running.completed(
            ExecutionHandleStatus.SUCCEEDED,
            "v1.protected-result",
            null,
            NOW.plusSeconds(2),
            Duration.ofDays(30)
        );
        assertThat(restarted.compareAndSet(running, succeeded)).isTrue();
        assertThat(restarted.findByIdempotencyFingerprint(
            queued.idempotencyFingerprint()
        )).contains(succeeded);
    }

    @Test
    void enforcesInvocationAndScopedIdempotencyUniqueness() {
        JdbcDurableExecutionRepository repository =
            new JdbcDurableExecutionRepository(dataSource(), true);
        DurableExecutionRecord first = queued("exec-1", "b");
        repository.create(first);

        assertThatThrownBy(() -> repository.create(first))
            .isInstanceOf(
                DurableExecutionRepository.DuplicateExecutionException.class
            );
        assertThatThrownBy(() ->
            repository.create(new DurableExecutionRecord(
                "exec-2",
                first.specialistId(),
                first.specialistContentHash(),
                first.accessFingerprint(),
                first.idempotencyFingerprint(),
                hash("c"),
                first.protectedRequest(),
                null,
                ExecutionHandleStatus.QUEUED,
                null,
                first.deadline(),
                first.createdAt(),
                first.updatedAt(),
                null,
                first.expiresAt(),
                null,
                null,
                0,
                0
            ))
        ).isInstanceOf(
            DurableExecutionRepository.DuplicateExecutionException.class
        );
    }

    @Test
    void findsQueuedAndExpiredLeaseWorkWithoutReclaimingActiveLease() {
        JdbcDurableExecutionRepository repository =
            new JdbcDurableExecutionRepository(dataSource(), true);
        DurableExecutionRecord queued = queued("exec-queued", "d");
        DurableExecutionRecord expiredLease = queued(
            "exec-expired",
            "e"
        ).claimed(
            "worker-old",
            NOW.minusSeconds(60),
            NOW.minusSeconds(1)
        );
        DurableExecutionRecord activeLease = queued(
            "exec-active",
            "f"
        ).claimed(
            "worker-live",
            NOW.minusSeconds(1),
            NOW.plusSeconds(60)
        );
        repository.create(queued);
        repository.create(expiredLease);
        repository.create(activeLease);

        assertThat(repository.findRecoverable(NOW, 10))
            .extracting(DurableExecutionRecord::invocationId)
            .containsExactlyInAnyOrder("exec-queued", "exec-expired");
    }

    @Test
    void completionRequiresTheExpectedLeaseOwnerAndVersion() {
        JdbcDurableExecutionRepository repository =
            new JdbcDurableExecutionRepository(dataSource(), true);
        DurableExecutionRecord queued = queued("exec-owned", "g");
        repository.create(queued);
        DurableExecutionRecord owned = queued.claimed(
            "worker-owner",
            NOW,
            NOW.plusSeconds(30)
        );
        assertThat(repository.compareAndSet(queued, owned)).isTrue();

        DurableExecutionRecord wrongOwner = withLeaseOwner(
            owned,
            "worker-other"
        );
        DurableExecutionRecord invalidCompletion = wrongOwner.completed(
            ExecutionHandleStatus.SUCCEEDED,
            "v1.protected-result",
            null,
            NOW.plusSeconds(1),
            Duration.ofDays(30)
        );
        assertThat(repository.compareAndSet(
            wrongOwner,
            invalidCompletion
        )).isFalse();

        DurableExecutionRecord completion = owned.completed(
            ExecutionHandleStatus.SUCCEEDED,
            "v1.protected-result",
            null,
            NOW.plusSeconds(1),
            Duration.ofDays(30)
        );
        assertThat(repository.compareAndSet(owned, completion)).isTrue();
    }

    @Test
    void retentionQueryAndCasDeleteLeaveRecentRowsUntouched() {
        JdbcDurableExecutionRepository repository =
            new JdbcDurableExecutionRepository(dataSource(), true);
        DurableExecutionRecord old = completed(
            queued("exec-old", "1"),
            NOW.minus(Duration.ofDays(40))
        );
        DurableExecutionRecord recent = completed(
            queued("exec-recent", "2"),
            NOW.minus(Duration.ofDays(2))
        );
        repository.create(old);
        repository.create(recent);

        assertThat(repository.findTerminalCompletedBefore(
            NOW.minus(Duration.ofDays(30)),
            10
        ))
            .extracting(DurableExecutionRecord::invocationId)
            .containsExactly("exec-old");
        assertThat(repository.delete(old)).isTrue();
        assertThat(repository.delete(old)).isFalse();
        assertThat(repository.findById(recent.invocationId()))
            .contains(recent);
    }

    private DurableExecutionRecord queued(String invocationId, String seed) {
        return DurableExecutionRecord.queued(
            invocationId,
            SpecialistId.of("account-resolver", "1"),
            hash("specialist"),
            hash("access"),
            hash("idempotency-" + seed),
            hash("request-" + seed),
            "v1.protected-request-" + seed,
            NOW.plusSeconds(300),
            NOW,
            Duration.ofDays(30)
        );
    }

    private DurableExecutionRecord completed(
        DurableExecutionRecord queued,
        Instant completedAt
    ) {
        DurableExecutionRecord running = queued.claimed(
            "worker",
            completedAt.minusSeconds(1),
            completedAt.plusSeconds(30)
        );
        return running.completed(
            ExecutionHandleStatus.SUCCEEDED,
            "v1.protected-result",
            null,
            completedAt,
            Duration.ofDays(30)
        );
    }

    private DurableExecutionRecord withLeaseOwner(
        DurableExecutionRecord record,
        String leaseOwner
    ) {
        return new DurableExecutionRecord(
            record.invocationId(),
            record.specialistId(),
            record.specialistContentHash(),
            record.accessFingerprint(),
            record.idempotencyFingerprint(),
            record.requestFingerprint(),
            record.protectedRequest(),
            record.protectedResult(),
            record.status(),
            record.failureReason(),
            record.deadline(),
            record.createdAt(),
            record.updatedAt(),
            record.completedAt(),
            record.expiresAt(),
            leaseOwner,
            record.leaseUntil(),
            record.attemptCount(),
            record.version()
        );
    }

    private String hash(String value) {
        return CanonicalJsonSupport.sha256(value);
    }

    private JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:durable-execution-"
                + java.util.UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
