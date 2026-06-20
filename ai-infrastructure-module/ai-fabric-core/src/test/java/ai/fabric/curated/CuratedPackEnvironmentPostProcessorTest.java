package ai.fabric.curated;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class CuratedPackEnvironmentPostProcessorTest {

    @Test
    void springFactoriesRegistersBoot4EnvironmentPostProcessorKey() throws Exception {
        Properties factories = PropertiesLoaderUtils.loadProperties(new ClassPathResource("META-INF/spring.factories"));

        assertThat(factories.getProperty("org.springframework.boot.EnvironmentPostProcessor"))
            .contains(CuratedPackEnvironmentPostProcessor.class.getName());
        assertThat(factories.getProperty("org.springframework.boot.env.EnvironmentPostProcessor"))
            .isNull();
    }

    @Test
    void loadsDefaultCuratedPackDefaults() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "ai.curated.pack", "default"
        )));

        new CuratedPackEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication(Object.class));

        OrchestrationProperties properties = Binder.get(environment)
            .bind("ai.orchestration", OrchestrationProperties.class)
            .orElseThrow(() -> new IllegalStateException("Failed to bind ai.orchestration"));

        assertThat(properties.getProfile()).isEqualTo(OrchestrationProfile.PRODUCTION_CHAT);
        assertThat(properties.isAlwaysGenerateInformation()).isTrue();
        assertThat(properties.getDefaultMode()).isEqualTo("thinker");
        assertThat(properties.getModes()).containsKeys("thinker", "executor");
        assertThat(properties.getModes().get("thinker").getRag().getFanoutEnabled()).isTrue();
    }
}
