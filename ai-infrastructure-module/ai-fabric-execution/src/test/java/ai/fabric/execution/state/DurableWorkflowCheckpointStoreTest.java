package ai.fabric.execution.state;

import static org.assertj.core.api.Assertions.*;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.intent.orchestration.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class DurableWorkflowCheckpointStoreTest {
    final Instant now = Instant.parse("2026-11-24T08:00:00Z");
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final DurableExecutionSecurity security = new DurableExecutionSecurity(mapper,
        "workflow-test-encryption-secret-0001", "workflow-test-fingerprint-secret-0002");
    final JdbcDurableExecutionRepository repository;
    final DurableExecutionRecord running;
    DurableWorkflowCheckpointStoreTest() {
        var source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:workflow-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        repository = new JdbcDurableExecutionRepository(source, true);
        var queued = DurableExecutionRecord.queued("work", SpecialistId.of("test", "1"), hash('a'), hash('b'),
            hash('c'), hash('d'), "protected-request", now.plusSeconds(120), now, Duration.ofDays(1));
        repository.create(queued); running = queued.claimed("worker", now, now.plusSeconds(120));
        assertThat(repository.compareAndSet(queued, running)).isTrue();
    }
    DurableWorkflowCheckpointStore store(DurableExecutionRecord record) {
        return new DurableWorkflowCheckpointStore(repository, security, mapper, clock, record);
    }
    WorkflowCheckpoint initial() {
        return new WorkflowCheckpoint("work", hash('d'), hash('b'), hash('a'), now.plusSeconds(120), 0,
            WorkflowAllowance.Usage.empty(), List.of(), WorkflowCheckpoint.State.RUNNING, null, List.of(), null);
    }
    static String hash(char c) { return String.valueOf(c).repeat(64); }

    @Test void encryptedCheckpointSurvivesAdapterRestartAndCasRejectsStaleWriter() {
        var first = store(running); var initial = initial();
        assertThat(first.create(initial)).isTrue();
        var record = repository.findById("work").orElseThrow();
        assertThat(record.protectedWorkflowCheckpoint()).startsWith("v1.").doesNotContain("authorityFingerprint", "workId");
        var restarted = store(record); assertThat(restarted.find("work")).contains(initial);
        var next = new WorkflowCheckpoint("work", hash('d'), hash('b'), hash('a'), initial.deadline(), 1,
            new WorkflowAllowance.Usage(1, 1, 1, 0), List.of(new WorkflowCheckpoint.Step("work:step:1", "write", true,
                Map.of("value", 7), hash('e'), WorkflowCheckpoint.Effect.EXECUTED, Map.of("receiptId", "receipt"), null)),
            WorkflowCheckpoint.State.RUNNING, null, List.of(), null);
        assertThat(restarted.compareAndSet(initial, next)).isTrue();
        assertThat(first.compareAndSet(initial, next)).isFalse();
        assertThat(security.canonicalHash(restarted.find("work").orElseThrow())).isEqualTo(security.canonicalHash(next));
        var completed = new WorkflowCheckpoint("work", hash('d'), hash('b'), hash('a'), initial.deadline(), 2,
            next.usage(), next.steps(), WorkflowCheckpoint.State.COMPLETE, "Done", List.of(), null);
        assertThat(restarted.compareAndSet(next, completed)).isTrue();
    }
    @Test void completionPreservesCheckpointSeparateFromFinalResult() {
        var store = store(running); assertThat(store.create(initial())).isTrue();
        var current = repository.findById("work").orElseThrow();
        var terminal = current.completed(ExecutionHandleStatus.SUCCEEDED, "final-result", null, now, Duration.ofDays(1));
        assertThat(repository.compareAndSet(current, terminal)).isTrue();
        assertThat(repository.findById("work").orElseThrow().protectedWorkflowCheckpoint()).isEqualTo(current.protectedWorkflowCheckpoint());
        assertThatThrownBy(() -> store.verifyLease("work")).hasMessage("WORKFLOW_EXECUTION_LEASE_LOST");
    }
    @Test void swappedExecutionAndAuthorityAreDenied() {
        var store = store(running);
        assertThatThrownBy(() -> store.find("other")).hasMessage("WORKFLOW_EXECUTION_MISMATCH");
        var wrong = new WorkflowCheckpoint("work", hash('d'), hash('f'), hash('a'), now.plusSeconds(120), 0,
            WorkflowAllowance.Usage.empty(), List.of(), WorkflowCheckpoint.State.RUNNING, null, List.of(), null);
        assertThatThrownBy(() -> store.create(wrong)).hasMessage("WORKFLOW_EXECUTION_SCOPE_MISMATCH");
    }
    @Test void cancellationPreventsLateCheckpointWrites() {
        var store = store(running); store.create(initial());
        var current = repository.findById("work").orElseThrow();
        var cancelled = current.completed(ExecutionHandleStatus.CANCELLED, null, "cancelled", now, Duration.ofDays(1));
        assertThat(repository.compareAndSet(current, cancelled)).isTrue();
        assertThatThrownBy(() -> store.create(initial())).hasMessage("WORKFLOW_EXECUTION_LEASE_LOST");
    }
}
