package com.ai.infrastructure.event.policy;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PIIDetectedEvent {
    String requestId;
    String userId;
    String piiType;
    String location;
    String severity;
    LocalDateTime timestamp;
}
