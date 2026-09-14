package ai.fabric.execution.chain.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.chain.SpecialistChainExecutionStatus;
import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import java.time.Duration;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcSpecialistChainExecutionRepositoryTest {

    private static final Instant NOW =
        Instant.parse("2026-09-13T12:00:00Z");

    @Test
    void checkpointSurvivesRestartAndUsesOptimisticTransitions() {
        JdbcDataSource dataSource = dataSource();
        JdbcSpecialistChainExecutionRepository first =
            new JdbcSpecialistChainExecutionRepository(dataSource, true);
        SpecialistChainExecutionRecord queued = queued("chain-1", "a");
        first.create(queued);

        JdbcSpecialistChainExecutionRepository restarted =
            new JdbcSpecialistChainExecutionRepository(dataSource, false);
        SpecialistChainExecutionRecord restored = restarted
            .findById(queued.executionId())
            .orElseThrow();
        assertThat(restored).isEqualTo(queued);

        SpecialistChainExecutionRecord running = restored.claimed(
            "worker-1",
            NOW.plusSeconds(1),
            NOW.plusSeconds(31)
        );
        assertThat(restarted.compareAndSet(restored, running)).isTrue();
        SpecialistChainExecutionRecord checkpointed =
            running.checkpointed(
                "v1.protected-checkpoint-2",
                1,
                NOW.plusSeconds(2),
                NOW.plusSeconds(32)
            );
        assertThat(restarted.compareAndSet(running, checkpointed)).isTrue();
        assertThat(restarted.compareAndSet(running, checkpointed)).isFalse();

        SpecialistChainExecutionRecord completed =
            checkpointed.completed(
                SpecialistChainExecutionStatus.COMPLETED,
                "v1.protected-result",
                null,
                NOW.plusSeconds(3),
                Duration.ofDays(30)
            );
        assertThat(restarted.compareAndSet(
            checkpointed,
            completed
        )).isTrue();
        assertThat(restarted.findByIdempotencyFingerprint(
            queued.idempotencyFingerprint()
        )).contains(completed);
    }

    @Test
    void enforcesScopedIdempotencyUniqueness() {
        JdbcSpecialistChainExecutionRepository repository =
            new JdbcSpecialistChainExecutionRepository(dataSource(), true);
        SpecialistChainExecutionRecord first = queued("chain-1", "b");
        repository.create(first);

        assertThatThrownBy(() -> repository.create(first))
            .isInstanceOf(
                SpecialistChainExecutionRepository
                    .DuplicateChainExecutionException.class
            );
        SpecialistChainExecutionRecord duplicateFingerprint =
            new SpecialistChainExecutionRecord(
                "chain-2",
                first.chainId(),
                first.chainContentHash(),
                first.managerSpecialistId(),
                first.managerContentHash(),
                first.accessFingerprint(),
                first.idempotencyFingerprint(),
                hash("different-request"),
                first.protectedRequest(),
                first.protectedCheckpoint(),
                null,
                SpecialistChainExecutionStatus.QUEUED,
                null,
                0,
                first.deadline(),
                first.createdAt(),
                first.updatedAt(),
                null,
                first.expiresAt(),
                null,
                null,
                0,
                0
            );
        assertThatThrownBy(() ->
            repository.create(duplicateFingerprint)
        ).isInstanceOf(
            SpecialistChainExecutionRepository
                .DuplicateChainExecutionException.class
        );
    }

    @Test
    void recoveryFindsQueuedAndExpiredLeasesOnly() {
        JdbcSpecialistChainExecutionRepository repository =
            new JdbcSpecialistChainExecutionRepository(dataSource(), true);
        SpecialistChainExecutionRecord queued = queued("queued", "c");
        SpecialistChainExecutionRecord expired = queued(
            "expired",
            "d"
        ).claimed(
            "old-worker",
            NOW.minusSeconds(60),
            NOW.minusSeconds(1)
        );
        SpecialistChainExecutionRecord active = queued(
            "active",
            "e"
        ).claimed(
            "live-worker",
            NOW.minusSeconds(1),
            NOW.plusSeconds(60)
        );
        repository.create(queued);
        repository.create(expired);
        repository.create(active);

        assertThat(repository.findRecoverable(NOW, 10))
            .extracting(SpecialistChainExecutionRecord::executionId)
            .containsExactlyInAnyOrder("queued", "expired");
        assertThat(repository.countActive()).isEqualTo(3);
    }

    @Test
    void completionRequiresTheExpectedLeaseAndVersion() {
        JdbcSpecialistChainExecutionRepository repository =
            new JdbcSpecialistChainExecutionRepository(dataSource(), true);
        SpecialistChainExecutionRecord queued = queued("owned", "f");
        repository.create(queued);
        SpecialistChainExecutionRecord running = queued.claimed(
            "owner",
            NOW,
            NOW.plusSeconds(30)
        );
        assertThat(repository.compareAndSet(queued, running)).isTrue();

        SpecialistChainExecutionRecord wrongOwner = withOwner(
            running,
            "other"
        );
        SpecialistChainExecutionRecord wrongCompletion =
            wrongOwner.completed(
                SpecialistChainExecutionStatus.COMPLETED,
                "v1.protected-result",
                null,
                NOW.plusSeconds(1),
                Duration.ofDays(30)
            );
        assertThat(repository.compareAndSet(
            wrongOwner,
            wrongCompletion
        )).isFalse();
    }

    @Test
    void retentionDeletesOnlyExpectedTerminalRows() {
        JdbcSpecialistChainExecutionRepository repository =
            new JdbcSpecialistChainExecutionRepository(dataSource(), true);
        SpecialistChainExecutionRecord old = completed(
            queued("old", "g"),
            NOW.minus(Duration.ofDays(40))
        );
        SpecialistChainExecutionRecord recent = completed(
            queued("recent", "h"),
            NOW.minus(Duration.ofDays(2))
        );
        SpecialistChainExecutionRecord active = queued(
            "active-old",
            "i"
        ).claimed(
            "active-worker",
            NOW.minus(Duration.ofDays(40)),
            NOW.plusSeconds(30)
        );
        repository.create(old);
        repository.create(recent);
        repository.create(active);

        assertThat(repository.findTerminalCompletedBefore(
            NOW.minus(Duration.ofDays(30)),
            10
        ))
            .extracting(SpecialistChainExecutionRecord::executionId)
            .containsExactly("old");
        assertThat(repository.delete(old)).isTrue();
        assertThat(repository.delete(old)).isFalse();
        assertThat(repository.findById("recent")).contains(recent);
        assertThat(repository.findById("active-old")).contains(active);
        assertThat(repository.delete(active)).isFalse();
    }

    private SpecialistChainExecutionRecord queued(
        String executionId,
        String seed
    ) {
        return SpecialistChainExecutionRecord.queued(
            executionId,
            SpecialistChainId.of("incident-chain", "1"),
            hash("chain"),
            SpecialistId.of("incident-manager", "1"),
            hash("manager"),
            hash("access"),
            hash("idempotency-" + seed),
            hash("request-" + seed),
            "v1.protected-request-" + seed,
            "v1.protected-checkpoint-" + seed,
            NOW.plusSeconds(300),
            NOW,
            Duration.ofDays(30)
        );
    }

    private SpecialistChainExecutionRecord completed(
        SpecialistChainExecutionRecord queued,
        Instant completedAt
    ) {
        return queued.claimed(
            "worker",
            completedAt.minusSeconds(1),
            completedAt.plusSeconds(30)
        ).completed(
            SpecialistChainExecutionStatus.COMPLETED,
            "v1.protected-result",
            null,
            completedAt,
            Duration.ofDays(30)
        );
    }

    private SpecialistChainExecutionRecord withOwner(
        SpecialistChainExecutionRecord record,
        String owner
    ) {
        return new SpecialistChainExecutionRecord(
            record.executionId(),
            record.chainId(),
            record.chainContentHash(),
            record.managerSpecialistId(),
            record.managerContentHash(),
            record.accessFingerprint(),
            record.idempotencyFingerprint(),
            record.requestFingerprint(),
            record.protectedRequest(),
            record.protectedCheckpoint(),
            record.protectedResult(),
            record.status(),
            record.failureReason(),
            record.nextDecisionIndex(),
            record.deadline(),
            record.createdAt(),
            record.updatedAt(),
            record.completedAt(),
            record.expiresAt(),
            owner,
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
            "jdbc:h2:mem:specialist-chain-"
                + java.util.UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
