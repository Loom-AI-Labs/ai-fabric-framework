package ai.fabric.curated.support;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.curated.CuratedPackEnvironmentPostProcessor;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
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
        assertThat(props.isAlwaysGenerateInformation()).isTrue();
        assertThat(props.getDefaultMode()).isEqualTo("support_assistant");
        assertThat(props.getModes()).containsKeys("support_assistant", "support_deep", "support_operator");
        assertThat(props.getModes().get("support_assistant").getActionsPreferred()).isEqualTo(true);
        assertThat(props.getModes().get("support_deep").getDeepRetrievalEnabled()).isTrue();
        assertThat(props.getModes().get("support_deep").getRag()).isNotNull();
        assertThat(props.getModes().get("support_deep").getRag().getFanoutEnabled()).isTrue();
        assertThat(props.getModes().get("support_deep").getRag().getMaxSpaces()).isEqualTo(6);
        assertThat(props.getModes().get("support_operator").getKnowledgeBaseOverviewEnabled()).isFalse();
        assertThat(environment.getProperty("ai.prompts.bundle.overlays[0]")).isNull();
        assertThat(props.getPositionRouting())
            .containsEntry("support", "support_assistant")
            .containsEntry("troubleshooting", "support_deep")
            .containsEntry("operations", "support_operator");
    }
}
