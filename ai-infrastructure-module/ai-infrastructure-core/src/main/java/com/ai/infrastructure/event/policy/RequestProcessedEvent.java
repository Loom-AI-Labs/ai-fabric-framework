package com.ai.infrastructure.event.policy;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RequestProcessedEvent {
    String requestId;
    String userId;
    String resourceId;
    String operationType;
    int recordCount;
    long processingTimeMs;
    LocalDateTime timestamp;
}
