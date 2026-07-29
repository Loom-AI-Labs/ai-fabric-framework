package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.specialist.SpecialistId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class EphemeralExecutionStoreTest {

    @Test
    void cancellationBeforeWorkerStartPreventsRunningTransition() {
        MutableClock clock = new MutableClock(
            Instant.parse("2026-07-28T10:00:00Z")
        );
        EphemeralExecutionStore store =
            new EphemeralExecutionStore(clock, Duration.ofMinutes(5));
        EphemeralExecutionStore.Entry entry = store.create(
            "exec-1",
            "request-1",
            null,
            ExecutionHandleStatus.QUEUED,
            null
        );

        assertThat(store.cancel(entry)).isTrue();
        assertThat(store.markRunning(entry)).isFalse();
        assertThat(store.snapshot(entry).handle().status())
            .isEqualTo(ExecutionHandleStatus.CANCELLED);
    }

    @Test
    void duplicateLiveIdempotencyKeyIsRejectedUntilEntryExpires() {
        MutableClock clock = new MutableClock(
            Instant.parse("2026-07-28T10:00:00Z")
        );
        EphemeralExecutionStore store =
            new EphemeralExecutionStore(clock, Duration.ofMinutes(5));
        store.create(
            "exec-1",
            "request-1",
            null,
            ExecutionHandleStatus.QUEUED,
            null
        );

        assertThatThrownBy(() -> store.create(
            "exec-2",
            "request-1",
            null,
            ExecutionHandleStatus.QUEUED,
            null
        )).isInstanceOf(
            EphemeralExecutionStore.DuplicateIdempotencyKeyException.class
        );

        clock.advance(Duration.ofMinutes(6));
        EphemeralExecutionStore.Entry replacement = store.create(
            "exec-3",
            "request-1",
            null,
            ExecutionHandleStatus.QUEUED,
            null
        );
        assertThat(store.snapshot(replacement).handle().status())
            .isEqualTo(ExecutionHandleStatus.QUEUED);
    }

    @Test
    void completedResultsExpireAndAreExplicitlyEphemeral() {
        MutableClock clock = new MutableClock(
            Instant.parse("2026-07-28T10:00:00Z")
        );
        EphemeralExecutionStore store =
            new EphemeralExecutionStore(clock, Duration.ofMinutes(5));
        EphemeralExecutionStore.Entry entry = store.create(
            "exec-1",
            null,
            null,
            ExecutionHandleStatus.RUNNING,
            null
        );
        store.complete(
            entry,
            new AIExecutionResult<>(
                "exec-1",
                SpecialistId.of("resolver", "1"),
                AIExecutionStatus.SUCCEEDED,
                "done",
                java.util.List.of(),
                java.util.Map.of(),
                null,
                clock.instant(),
                clock.instant()
            )
        );

        assertThat(store.snapshot(entry).handle().durability())
            .isEqualTo(ExecutionDurability.EPHEMERAL);
        assertThat(store.find("exec-1")).isPresent();

        clock.advance(Duration.ofMinutes(6));
        assertThat(store.find("exec-1")).isEmpty();
    }

    @Test
    void handlesAreLostWhenAProcessCreatesANewStore() {
        MutableClock clock = new MutableClock(
            Instant.parse("2026-07-28T10:00:00Z")
        );
        EphemeralExecutionStore firstProcess =
            new EphemeralExecutionStore(clock, Duration.ofMinutes(5));
        firstProcess.create(
            "exec-before-restart",
            "request-before-restart",
            null,
            ExecutionHandleStatus.QUEUED,
            null
        );

        EphemeralExecutionStore restartedProcess =
            new EphemeralExecutionStore(clock, Duration.ofMinutes(5));

        assertThat(restartedProcess.find("exec-before-restart")).isEmpty();
        assertThat(restartedProcess
            .replay(
                "request-before-restart",
                null,
                "request-before-restart"
            )
            .status())
            .isEqualTo(
                EphemeralExecutionStore.IdempotencyReplayStatus.MISSING
            );
    }

    @Test
    void runningEntryDoesNotExpireFromResultTtlBeforeItsDeadline() {
        MutableClock clock = new MutableClock(
            Instant.parse("2026-07-28T10:00:00Z")
        );
        EphemeralExecutionStore store =
            new EphemeralExecutionStore(clock, Duration.ofMinutes(1));
        EphemeralExecutionStore.Entry entry = store.create(
            "exec-running",
            "request-running",
            clock.instant().plus(Duration.ofMinutes(5)),
            ExecutionHandleStatus.RUNNING,
            null
        );

        clock.advance(Duration.ofMinutes(2));

        assertThat(store.find("exec-running")).contains(entry);
        assertThat(store.replay(
            "request-running",
            null,
            "request-running"
        ).entry()).isSameAs(entry);
    }

    @Test
    void overdueRunningEntryFailsAndRetainsTheFailureForTheResultTtl() {
        MutableClock clock = new MutableClock(
            Instant.parse("2026-07-28T10:00:00Z")
        );
        EphemeralExecutionStore store =
            new EphemeralExecutionStore(clock, Duration.ofMinutes(1));
        EphemeralExecutionStore.Entry entry = store.create(
            "exec-running",
            null,
            clock.instant().plus(Duration.ofMinutes(2)),
            ExecutionHandleStatus.RUNNING,
            null
        );

        clock.advance(Duration.ofMinutes(2));

        assertThat(store.find("exec-running")).hasValueSatisfying(found -> {
            ExecutionHandle handle = store.snapshot(found).handle();
            assertThat(handle.status()).isEqualTo(ExecutionHandleStatus.FAILED);
            assertThat(handle.failureReason()).isEqualTo("DEADLINE_EXCEEDED");
        });

        clock.advance(Duration.ofSeconds(61));
        assertThat(store.find("exec-running")).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
