package com.ai.curated.support;

import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.curated.CuratedPackEnvironmentPostProcessor;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationProfile;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SupportCuratedPackTest {

    @Test
    void shouldLoadSupportPackDefaults() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "ai.curated.pack", "support"
        )));

        new CuratedPackEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication(Object.class));

        OrchestrationProperties props = Binder.get(environment)
            .bind("ai.orchestration", OrchestrationProperties.class)
            .orElseThrow(() -> new IllegalStateException("Failed to bind ai.orchestration"));

        assertThat(props.getProfile()).isEqualTo(OrchestrationProfile.PRODUCTION_CHAT);
        assertThat(props.getDefaultMode()).isEqualTo("support_assistant");
        assertThat(props.getModes()).containsKeys("support_assistant", "support_deep", "support_operator");
        assertThat(props.getModes().get("support_assistant").getActionsPreferred()).isEqualTo(true);
        assertThat(props.getModes().get("support_deep").getUseAdvancedRag()).isNull();
        assertThat(props.getPositionRouting())
            .containsEntry("support", "support_assistant")
            .containsEntry("troubleshooting", "support_deep")
            .containsEntry("operations", "support_operator");
    }
}
