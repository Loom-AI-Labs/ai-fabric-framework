package ai.fabric.execution.gateway;

import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local, one-use registry for approved conversation snapshots.
 */
public final class EphemeralAIExecutionConversationSnapshotRegistry
    implements AIExecutionConversationSnapshotRegistry {

    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentMap<String, Entry> entries =
        new ConcurrentHashMap<>();

    public EphemeralAIExecutionConversationSnapshotRegistry(
        Clock clock,
        Duration ttl
    ) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.ttl = ttl;
    }

    @Override
    public ConversationBinding approve(
        ConversationBinding binding,
        ApprovedConversationSnapshot snapshot
    ) {
        requirePlainBinding(binding);
        Objects.requireNonNull(snapshot, "snapshot is required");
        validateBinding(binding, snapshot);
        cleanupExpired();

        String token = "snapshot-" + UUID.randomUUID();
        entries.put(
            token,
            new Entry(snapshot, clock.instant().plus(ttl))
        );
        return new ConversationBinding(
            binding.userId(),
            binding.conversationId(),
            token
        );
    }

    @Override
    public ApprovedConversationSnapshot consume(ConversationBinding binding) {
        Objects.requireNonNull(binding, "binding is required");
        String token = binding.approvedSnapshotToken();
        if (token == null) {
            throw new IllegalArgumentException(
                "Approved conversation snapshot token is required"
            );
        }
        Entry entry = entries.get(token);
        if (entry == null) {
            throw new IllegalArgumentException(
                "Approved conversation snapshot is unavailable or expired"
            );
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            entries.remove(token, entry);
            throw new IllegalArgumentException(
                "Approved conversation snapshot is unavailable or expired"
            );
        }
        validateBinding(binding, entry.snapshot());
        if (!entries.remove(token, entry)) {
            throw new IllegalArgumentException(
                "Approved conversation snapshot is unavailable or expired"
            );
        }
        return entry.snapshot();
    }

    @Override
    public void release(ConversationBinding binding) {
        if (binding != null && binding.approvedSnapshotToken() != null) {
            entries.remove(binding.approvedSnapshotToken());
        }
    }

    private void requirePlainBinding(ConversationBinding binding) {
        Objects.requireNonNull(binding, "binding is required");
        if (binding.approvedSnapshotToken() != null) {
            throw new IllegalArgumentException(
                "A caller cannot replace an approved snapshot token"
            );
        }
    }

    private void validateBinding(
        ConversationBinding binding,
        ApprovedConversationSnapshot snapshot
    ) {
        if (!binding.userId().equals(snapshot.ownerId())
            || !binding.conversationId().equals(snapshot.conversationId())) {
            throw new IllegalArgumentException(
                "Approved snapshot does not match the conversation binding"
            );
        }
    }

    private void cleanupExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(
            entry -> !now.isBefore(entry.getValue().expiresAt())
        );
    }

    private record Entry(
        ApprovedConversationSnapshot snapshot,
        Instant expiresAt
    ) {}
}
