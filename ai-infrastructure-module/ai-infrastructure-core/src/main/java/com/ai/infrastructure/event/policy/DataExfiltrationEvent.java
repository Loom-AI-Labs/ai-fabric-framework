package com.ai.infrastructure.event.policy;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class DataExfiltrationEvent {
    String requestId;
    String userId;
    String destination;
    String pattern;
    LocalDateTime timestamp;
}
