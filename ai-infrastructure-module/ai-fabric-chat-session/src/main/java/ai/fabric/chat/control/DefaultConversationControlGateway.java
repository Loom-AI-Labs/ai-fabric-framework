package ai.fabric.chat.control;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Default state-machine implementation over a host-selected durable store. */
public final class DefaultConversationControlGateway implements ConversationControlGateway {

    private static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(5);
    private static final int MAX_MESSAGES = 500;

    private final ConversationControlStore store;
    private final Clock clock;

    public DefaultConversationControlGateway(ConversationControlStore store) {
        this(store, Clock.systemUTC());
    }

    public DefaultConversationControlGateway(ConversationControlStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public ConversationControlSnapshot status(ConversationScope scope) {
        return store.getOrCreate(scope, clock.instant());
    }

    @Override
    public ConversationControlResult transition(ConversationControlCommand command) {
        Transition transition = transitionFor(command.type());
        return store.applyTransition(
            command,
            transition.requiredState(),
            transition.targetState(),
            transition.advanceEpoch(),
            clock.instant()
        );
    }

    @Override
    public ConversationTurnLease acquireAiTurnLease(ConversationScope scope, Duration ttl) {
        ConversationControlSnapshot snapshot = status(scope);
        if (snapshot.state() != ConversationControlState.AI_ACTIVE) {
            throw denied(snapshot.state(), "AI_TURN_NOT_ALLOWED");
        }
        Duration effectiveTtl = ttl == null || ttl.isZero() || ttl.isNegative()
            ? DEFAULT_LEASE_TTL
            : ttl;
        Instant now = clock.instant();
        return new ConversationTurnLease(
            scope,
            "turn-" + UUID.randomUUID(),
            snapshot.controlEpoch(),
            now,
            now.plus(effectiveTtl)
        );
    }

    @Override
    public void verifyAiTurnLease(ConversationTurnLease lease) {
        Objects.requireNonNull(lease, "lease is required");
        ConversationControlSnapshot snapshot = status(lease.scope());
        if (!clock.instant().isBefore(lease.expiresAt())) {
            throw new ConversationControlViolationException("AI_TURN_LEASE_EXPIRED", "The AI turn lease has expired.");
        }
        if (snapshot.state() != ConversationControlState.AI_ACTIVE
            || snapshot.controlEpoch() != lease.controlEpoch()) {
            throw denied(snapshot.state(), "AI_TURN_LEASE_STALE");
        }
    }

    @Override
    public ConversationMessage appendMessage(
        ConversationMessageCommand command,
        ConversationTurnLease aiLease
    ) {
        ConversationControlSnapshot snapshot = status(command.scope());
        switch (command.role()) {
            case AI -> {
                if (aiLease == null || !aiLease.scope().equals(command.scope())) {
                    throw new ConversationControlViolationException(
                        "AI_TURN_LEASE_REQUIRED",
                        "An AI message requires the active turn lease."
                    );
                }
                verifyAiTurnLease(aiLease);
            }
            case HUMAN -> {
                if (snapshot.state() != ConversationControlState.HUMAN_ACTIVE) {
                    throw denied(snapshot.state(), "HUMAN_MESSAGE_NOT_ALLOWED");
                }
            }
            case USER -> {
                if (snapshot.state() == ConversationControlState.CLOSED
                    || snapshot.state() == ConversationControlState.AI_RESUME_PENDING) {
                    throw denied(snapshot.state(), "USER_MESSAGE_NOT_ALLOWED");
                }
            }
            case SYSTEM -> {
                // System events are still subject to ownership scope in the store.
            }
        }
        return store.appendMessage(command, clock.instant());
    }

    @Override
    public List<ConversationMessage> messages(
        ConversationScope scope,
        long afterSequence,
        int limit
    ) {
        int bounded = Math.max(1, Math.min(limit, MAX_MESSAGES));
        return store.listMessages(scope, Math.max(0, afterSequence), bounded);
    }

    private Transition transitionFor(ConversationControlCommandType type) {
        return switch (type) {
            case REQUEST_HUMAN -> new Transition(
                ConversationControlState.AI_ACTIVE,
                ConversationControlState.HUMAN_PENDING,
                true
            );
            case ACTIVATE_HUMAN -> new Transition(
                ConversationControlState.HUMAN_PENDING,
                ConversationControlState.HUMAN_ACTIVE,
                false
            );
            case CANCEL_HUMAN_REQUEST -> new Transition(
                ConversationControlState.HUMAN_PENDING,
                ConversationControlState.AI_ACTIVE,
                true
            );
            case REQUEST_AI_RESUME -> new Transition(
                ConversationControlState.HUMAN_ACTIVE,
                ConversationControlState.AI_RESUME_PENDING,
                true
            );
            case COMPLETE_AI_RESUME -> new Transition(
                ConversationControlState.AI_RESUME_PENDING,
                ConversationControlState.AI_ACTIVE,
                true
            );
            case CLOSE -> new Transition(null, ConversationControlState.CLOSED, true);
        };
    }

    private ConversationControlViolationException denied(
        ConversationControlState state,
        String code
    ) {
        return new ConversationControlViolationException(
            code,
            "Conversation control state " + state + " does not allow this operation."
        );
    }

    private record Transition(
        ConversationControlState requiredState,
        ConversationControlState targetState,
        boolean advanceEpoch
    ) {}
}
