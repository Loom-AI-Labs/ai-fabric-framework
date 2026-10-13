package ai.fabric.chat.control;

import java.time.Instant;
import org.springframework.util.StringUtils;

/** Immutable control-epoch lease for one AI orchestration turn. */
public record ConversationTurnLease(
    ConversationScope scope,
    String leaseId,
    long controlEpoch,
    Instant acquiredAt,
    Instant expiresAt
) {
    public ConversationTurnLease {
        if (scope == null || !StringUtils.hasText(leaseId) || acquiredAt == null || expiresAt == null) {
            throw new IllegalArgumentException("scope, leaseId, acquiredAt and expiresAt are required");
        }
        if (controlEpoch < 0 || !expiresAt.isAfter(acquiredAt)) {
            throw new IllegalArgumentException("lease epoch/expiry is invalid");
        }
        leaseId = leaseId.trim();
    }
}
