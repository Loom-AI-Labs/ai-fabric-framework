package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrdinaryChatCapabilityProfileTest {

    @Test
    void absentOptionalAllowlistKeepsRegisteredSourcesWithoutEnablingFanout() {
        var result = resolve(true, false, List.of(), Set.of(" RECORDS ", "Policy"), null);

        assertThat(result.getEffectiveCapabilityProfile().effectiveVectorSpaces())
            .containsExactlyInAnyOrder("records", "policy");
        assertThat(result.getOrchestrationPolicy().ragBudgets().retrievalVectorSpacesAllowlist()).isEmpty();
        assertThat(result.getOrchestrationPolicy().ragBudgets().fanoutEnabled()).isNotEqualTo(true);
    }

    @Test
    void configuredAllowlistNarrowsRegisteredSourcesAndDoesNotInventUnregisteredNames() {
        var result = resolve(true, false, List.of("policy", "unregistered"), Set.of("records", "policy"), null);

        assertThat(result.getEffectiveCapabilityProfile().effectiveVectorSpaces()).containsExactly("policy");
    }

    @Test
    void requiredAllowlistCannotBeReplacedWithRegistration() {
        var result = resolve(true, true, List.of(), Set.of("records", "policy"), null);

        assertThat(result.getEffectiveCapabilityProfile().effectiveVectorSpaces()).isEmpty();
        assertThat(result.getOrchestrationPolicy().capabilities().retrievalAllowlistRequired()).isTrue();
    }

    @Test
    void disabledRetrievalStillHasNoEffectiveSources() {
        var result = resolve(false, false, List.of(), Set.of("records", "policy"), null);

        assertThat(result.getEffectiveCapabilityProfile().effectiveVectorSpaces()).isEmpty();
        assertThat(result.getEffectiveCapabilityProfile().retrievalEnabled()).isFalse();
    }

    @Test
    void absentRegistryDoesNotInventSourcesFromAnAllowlist() {
        var result = resolve(true, false, List.of("policy"), null, null);

        assertThat(result.getEffectiveCapabilityProfile().effectiveVectorSpaces()).isEmpty();
    }

    @Test
    void emptyRegistryDoesNotInventSourcesFromAnAllowlist() {
        var result = resolve(true, false, List.of("policy"), Set.of(), null);

        assertThat(result.getEffectiveCapabilityProfile().effectiveVectorSpaces()).isEmpty();
    }

    @Test
    void explicitEmptyTaskProfileRemainsAnEmptyCeiling() {
        var explicit = profile(Set.of());
        var result = resolve(true, false, List.of(), Set.of("records", "policy"), explicit);

        assertThat(result.getEffectiveCapabilityProfile()).isSameAs(explicit);
        assertThat(result.getEffectiveCapabilityProfile().effectiveVectorSpaces()).isEmpty();
        assertThat(result.getOrchestrationPolicy().capabilities().retrievalAllowlistRequired()).isTrue();
    }

    @Test
    void explicitTaskProfileIsNotExpandedToEveryRegisteredSource() {
        var explicit = profile(Set.of("policy"));
        var result = resolve(true, false, List.of(), Set.of("records", "policy"), explicit);

        assertThat(result.getEffectiveCapabilityProfile()).isSameAs(explicit);
        assertThat(result.getEffectiveCapabilityProfile().effectiveVectorSpaces()).containsExactly("policy");
        assertThat(result.getOrchestrationPolicy().ragBudgets().retrievalVectorSpacesAllowlist())
            .containsExactly("policy");
    }

    private EffectiveCapabilityProfile profile(Set<String> spaces) {
        return new EffectiveCapabilityProfile("task", "resolver", true, spaces, Set.of(), Set.of(), Set.of(),
            new OrchestrationPolicy.RagBudgets(false, 1, 3, 3, 3, 2_000, List.copyOf(spaces)),
            OrchestrationPolicy.ReadActionResolutionPolicy.defaults(), "explicit-task-profile");
    }

    private PipelineContext resolve(boolean retrieval, boolean required, List<String> allowlist,
                                    Set<String> registered, EffectiveCapabilityProfile explicit) {
        var properties = new OrchestrationProperties();
        var mode = new OrchestrationProperties.ModeOverrides();
        mode.setRetrievalEnabled(retrieval);
        mode.setRetrievalAllowlistRequired(required);
        var rag = new OrchestrationProperties.RagModeOverrides();
        rag.setFanoutEnabled(false);
        rag.setRetrievalVectorSpacesAllowlist(allowlist);
        mode.setRag(rag);
        properties.getModes().put("resolver", mode);
        properties.setDefaultMode("resolver");
        var registry = mock(AIActionRegistry.class);
        when(registry.getAllMetadata()).thenReturn(List.of());
        try (var beans = new AnnotationConfigApplicationContext()) {
            beans.registerBean(OrchestrationProperties.class, () -> properties);
            beans.registerBean(AIActionRegistry.class, () -> registry);
            if (registered != null) {
                var entities = mock(AIEntityConfigurationLoader.class);
                when(entities.getSupportedEntityTypes()).thenReturn(registered);
                beans.registerBean(AIEntityConfigurationLoader.class, () -> entities);
            }
            beans.register(OrchestrationPolicyResolutionStep.class);
            beans.refresh();
            var context = PipelineContext.from("Read approved information", OrchestrationContext.forUser("user-1"))
                .toBuilder().effectiveCapabilityProfile(explicit).build();
            return beans.getBean(OrchestrationPolicyResolutionStep.class).process(context);
        }
    }
}
