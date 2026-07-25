package ai.fabric.behavior.config;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.relationship.service.EntityRelationshipMapper;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BehaviorConfigTests {

    @Test
    void fullModeRegistersAnEnabledTypedPolicyWithoutOverride() {
        AIEntityConfigurationLoader loader = Mockito.mock(AIEntityConfigurationLoader.class);
        when(loader.hasEntityConfig("behavior-insight")).thenReturn(false);
        BehaviorAIAutoConfiguration config = new BehaviorAIAutoConfiguration(loader);
        ReflectionTestUtils.setField(config, "mode", "FULL");

        config.registerBehaviorConfig();

        ArgumentCaptor<AIEntityConfig> policy =
            ArgumentCaptor.forClass(AIEntityConfig.class);
        verify(loader).registerEntityConfig(
            Mockito.eq("behavior-insight"),
            policy.capture(),
            Mockito.eq(false)
        );
        assertThat(policy.getValue().getIndexing().getEnabled()).isTrue();
        assertThat(policy.getValue().getIndexing().getMaxCharacters())
            .isEqualTo(8000);
    }

    @Test
    void applicationPolicyTakesPrecedenceOverThePreset() {
        AIEntityConfigurationLoader loader = Mockito.mock(AIEntityConfigurationLoader.class);
        when(loader.hasEntityConfig("behavior-insight")).thenReturn(true);
        BehaviorAIAutoConfiguration config = new BehaviorAIAutoConfiguration(loader);
        ReflectionTestUtils.setField(config, "mode", "FULL");

        config.registerBehaviorConfig();

        verify(loader).hasEntityConfig("behavior-insight");
        verifyNoMoreInteractions(loader);
    }

    @Test
    void relationshipRegistrationRegistersEntity() {
        ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
        EntityRelationshipMapper mapper = Mockito.mock(EntityRelationshipMapper.class);
        Mockito.when(applicationContext.getBean(EntityRelationshipMapper.class)).thenReturn(mapper);

        BehaviorRelationshipRegistration registration = new BehaviorRelationshipRegistration(applicationContext);

        registration.registerRelationships();

        verify(mapper).registerEntityType(BehaviorInsights.class);
        verifyNoMoreInteractions(mapper);
    }
}
