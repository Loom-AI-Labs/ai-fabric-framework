package ai.fabric.behavior.config;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.relationship.service.EntityRelationshipMapper;
import ai.fabric.config.AIEntityConfigurationLoader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class BehaviorConfigTests {

    @Test
    void autoConfigurationLoadsPresetWithoutOverride() {
        AIEntityConfigurationLoader loader = Mockito.mock(AIEntityConfigurationLoader.class);
        BehaviorAIAutoConfiguration config = new BehaviorAIAutoConfiguration(loader);
        ReflectionTestUtils.setField(config, "mode", "FULL");

        config.registerBehaviorConfig();

        verify(loader).loadConfigurationFromFile("classpath:behavior-presets/behavior-ai-full.yml", false);
    }

    @Test
    void relationshipRegistrationRegistersEntity() {
        ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
        EntityRelationshipMapper mapper = Mockito.mock(EntityRelationshipMapper.class);
        Mockito.when(applicationContext.getBean(EntityRelationshipMapper.class)).thenReturn(mapper);

        BehaviorRelationshipRegistration registration = new BehaviorRelationshipRegistration(applicationContext);

        registration.registerRelationships();

        verify(mapper).registerEntityType(BehaviorInsights.class);
        assertThat(true).isTrue(); // placeholder to keep AssertJ usage consistent
    }
}
