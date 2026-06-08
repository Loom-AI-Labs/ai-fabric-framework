package com.ai.infrastructure.behavior.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BatchProcessingResult {
    private int processedCount;
    private int successCount;
    private int errorCount;
    private long durationMs;
}
