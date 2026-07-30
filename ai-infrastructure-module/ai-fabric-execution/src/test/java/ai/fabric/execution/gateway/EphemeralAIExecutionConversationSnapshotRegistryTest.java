package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.dto.AIChatMessage;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class EphemeralAIExecutionConversationSnapshotRegistryTest {

    @Test
    void bindsOneUseTokenToTheApprovedOwnerAndConversation() {
        EphemeralAIExecutionConversationSnapshotRegistry registry =
            new EphemeralAIExecutionConversationSnapshotRegistry(
                Clock.fixed(now(), ZoneOffset.UTC),
                Duration.ofMinutes(2)
            );
        ConversationBinding plain =
            new ConversationBinding("user-1", "conversation-1");

        ConversationBinding approved =
            registry.approve(plain, snapshot());

        assertThat(approved.userId()).isEqualTo("user-1");
        assertThat(approved.conversationId())
            .isEqualTo("conversation-1");
        assertThat(approved.approvedSnapshotToken())
            .startsWith("snapshot-");
        assertThat(registry.consume(approved)).isEqualTo(snapshot());
        assertThatThrownBy(() -> registry.consume(approved))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unavailable or expired");
    }

    @Test
    void mismatchedBindingCannotConsumeAnotherConversationSnapshot() {
        EphemeralAIExecutionConversationSnapshotRegistry registry =
            new EphemeralAIExecutionConversationSnapshotRegistry(
                Clock.fixed(now(), ZoneOffset.UTC),
                Duration.ofMinutes(2)
            );
        ConversationBinding approved = registry.approve(
            new ConversationBinding("user-1", "conversation-1"),
            snapshot()
        );
        ConversationBinding forged = new ConversationBinding(
            "other-user",
            "conversation-1",
            approved.approvedSnapshotToken()
        );

        assertThatThrownBy(() -> registry.consume(forged))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match");

        assertThat(registry.consume(approved)).isEqualTo(snapshot());
    }

    @Test
    void rejectsExpiredAndExplicitlyReleasedApprovals() {
        MutableClock clock = new MutableClock(now());
        EphemeralAIExecutionConversationSnapshotRegistry registry =
            new EphemeralAIExecutionConversationSnapshotRegistry(
                clock,
                Duration.ofSeconds(30)
            );
        ConversationBinding expired = registry.approve(
            new ConversationBinding("user-1", "conversation-1"),
            snapshot()
        );
        clock.advance(Duration.ofSeconds(31));

        assertThatThrownBy(() -> registry.consume(expired))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expired");

        ConversationBinding released = registry.approve(
            new ConversationBinding("user-1", "conversation-1"),
            snapshot()
        );
        registry.release(released);
        assertThatThrownBy(() -> registry.consume(released))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unavailable");
    }

    private ApprovedConversationSnapshot snapshot() {
        return new ApprovedConversationSnapshot(
            "turn-1",
            "user-1",
            "conversation-1",
            "resolver@1",
            "a".repeat(64),
            1,
            java.util.List.of(
                AIChatMessage.user("Why am I blocked?"),
                AIChatMessage.assistant("Payment is missing.")
            ),
            now()
        );
    }

    private Instant now() {
        return Instant.parse("2026-07-29T12:00:00Z");
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
