package ai.fabric.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Published active-request execution limits, not a caller-supplied authority grant. */
@Data
public class ConversationalAutonomyProperties {
    private boolean enabled;
    private boolean defaultEnabled = true;
    @Min(5) @Max(300) private int maxElapsedSeconds = 120;
    @Min(5) @Max(120) private int segmentSeconds = 30;
    @Min(1) @Max(64) private int maxActionsPerSegment = 8;
    @Min(0) @Max(64) private int maxWritesPerSegment = 3;
    @Min(1) @Max(64) private int maxModelCallsPerSegment = 12;
    @Min(0) @Max(8) private int maxCorrectionsPerSegment = 3;
    @Min(1) @Max(100) private int maxConsecutiveNoProgress = 8;
    @Min(1) @Max(20) private int maxConsecutivePlannerFailures = 4;
    private boolean replanNonAuthorityReadFailures = true;
    @Size(max = 8000) private String instructions = "";
}
