package ai.fabric.behavior.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScheduledControlResponse {
    private String message;
    private boolean paused;
}
