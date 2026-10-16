package ai.fabric.intent.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryPendingActionStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");

    @Test
    void compareAndConsumeDoesNotRemoveAChangedTopAction() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        PendingAction first = pending("first_action");
        PendingAction second = pending("second_action");
        store.pushPendingAction("conversation-1", "owner-1", first);
        store.pushPendingAction("conversation-1", "owner-1", second);

        assertThat(store.popPendingAction("conversation-1", "owner-1", first)).isEmpty();
        assertThat(store.getPendingActionStack("conversation-1", "owner-1"))
            .containsExactly(second, first);
        assertThat(store.popPendingAction("conversation-1", "owner-1", second)).contains(second);
        assertThat(store.getPendingActionStack("conversation-1", "owner-1"))
            .containsExactly(first);
    }

    @Test
    void compareAndReplaceDoesNotOverwriteAChangedStack() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        PendingAction first = pending("first_action");
        PendingAction second = pending("second_action");
        PendingAction replacement = pending("replacement_action");
        store.pushPendingAction("conversation-1", "owner-1", first);
        store.pushPendingAction("conversation-1", "owner-1", second);

        assertThat(store.replacePendingActionStack(
            "conversation-1",
            "owner-1",
            List.of(first),
            List.of(replacement)
        )).isFalse();
        assertThat(store.getPendingActionStack("conversation-1", "owner-1"))
            .containsExactly(second, first);
        assertThat(store.replacePendingActionStack(
            "conversation-1",
            "owner-1",
            List.of(second, first),
            List.of(replacement)
        )).isTrue();
        assertThat(store.getPendingActionStack("conversation-1", "owner-1"))
            .containsExactly(replacement);
    }

    private PendingAction pending(String action) {
        return new PendingAction(action, Map.of("id", action + "-id"), null, NOW);
    }
}
