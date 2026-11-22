package ai.fabric.config;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.assertj.core.api.Assertions.*;

class DecisionRecoveryPropertiesTest {
    @Test void platformEnvironmentNamesBindToTheActualFrameworkSettings() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("platform-test", Map.of(
            "AI_ORCHESTRATION_DECISION_RECOVERY_MAX_REPAIR_ROUNDS", "5",
            "AI_ORCHESTRATION_DECISION_RECOVERY_REPAIR_ADMISSION_TIMEOUT_MS", "45000",
            "AI_ORCHESTRATION_DECISION_RECOVERY_FINAL_CONTEXT_ANSWER_ENABLED", "true")));
        var settings = Binder.get(environment).bind("ai.orchestration.decision-recovery", DecisionRecoveryProperties.class).get();
        assertThat(settings.getMaxRepairRounds()).isEqualTo(5);
        assertThat(settings.getRepairAdmissionTimeoutMs()).isEqualTo(45000);
        assertThat(settings.isFinalContextAnswerEnabled()).isTrue();
    }
}
