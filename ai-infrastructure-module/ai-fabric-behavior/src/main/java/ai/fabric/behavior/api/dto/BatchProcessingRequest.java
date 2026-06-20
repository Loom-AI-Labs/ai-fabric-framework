package ai.fabric.behavior.api.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class BatchProcessingRequest {
    @Min(1)
    private Integer maxUsers;

    @Min(1)
    private Integer maxDurationMinutes;

    @Min(0)
    private Long delayBetweenUsersMs;
}
