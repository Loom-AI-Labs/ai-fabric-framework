package ai.fabric.behavior.api.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ContinuousProcessingRequest {
    @Min(1)
    private Integer usersPerBatch;

    @Min(0)
    private Integer intervalMinutes;

    @Min(1)
    private Integer maxIterations;
}
