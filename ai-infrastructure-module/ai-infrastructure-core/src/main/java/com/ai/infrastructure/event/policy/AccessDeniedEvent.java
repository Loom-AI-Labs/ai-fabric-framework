package com.ai.infrastructure.event.policy;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AccessDeniedEvent {
    String requestId;
    String userId;
    String resourceId;
    String reason;
    LocalDateTime timestamp;
}
