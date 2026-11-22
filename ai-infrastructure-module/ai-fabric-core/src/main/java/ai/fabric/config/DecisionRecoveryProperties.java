package ai.fabric.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "ai.orchestration.decision-recovery")
public class DecisionRecoveryProperties {
    private boolean enabled = true;
    @Min(0) @Max(10)
    private int maxRepairRounds = 3;
    /** Stops admitting more repairs; an in-flight call retains its provider timeout. */
    @Min(1000) @Max(300000)
    private long repairAdmissionTimeoutMs = 30000;
    private boolean finalContextAnswerEnabled = true;
}
