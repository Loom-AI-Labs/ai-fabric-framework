package com.ai.infrastructure.event.policy;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class InjectionEvent {
    String requestId;
    String userId;
    String injectionType;
    String pattern;
    LocalDateTime timestamp;
}
