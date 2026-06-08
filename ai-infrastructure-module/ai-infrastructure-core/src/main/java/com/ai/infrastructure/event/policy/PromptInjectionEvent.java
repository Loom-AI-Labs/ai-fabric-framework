package com.ai.infrastructure.event.policy;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PromptInjectionEvent {
    String requestId;
    String userId;
    String pattern;
    LocalDateTime timestamp;
}
