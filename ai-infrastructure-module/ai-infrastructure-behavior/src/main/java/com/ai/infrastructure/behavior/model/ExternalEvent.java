package com.ai.infrastructure.behavior.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ExternalEvent {
    private String eventType;
    private Map<String, Object> eventData;
    private LocalDateTime timestamp;
    private String source;
}
