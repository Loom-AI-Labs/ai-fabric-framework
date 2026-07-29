package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.specialist.SpecialistDefinitionValidator;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.intent.action.AIActionRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublishedSpecialistExampleTest {

    @Test
    void packagedConfigOnlyExampleLoadsAndCompilesThroughRuntimeBootstrap() {
        var mapper = ManifestTestFixtures.objectMapper();
        var schemaValidator = new SpecialistJsonSchemaValidator();
        AIExecutionProperties.Manifests properties =
            new AIExecutionProperties.Manifests();
        properties.setEnabled(true);
        properties.setLocations(List.of(
            "classpath:META-INF/ai-fabric/examples/"
                + "support-knowledge-specialist.yml"
        ));
        SpecialistResourceBundle resources =
            new DefaultSpecialistManifestLoader(mapper).load(properties);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        when(actionRegistry.getAllMetadata()).thenReturn(List.of());
        SpecialistDefinitionValidator definitionValidator =
            new SpecialistDefinitionValidator(
                actionRegistry,
                Set.of("deep"),
                Set.of("support-article", "support-policy")
            );

        SpecialistRegistryBootstrap bootstrap =
            new SpecialistRegistryBootstrap(
                List.of(),
                resources,
                new DefaultSpecialistManifestCompiler(),
                definitionValidator,
                new SpecialistGroundingValidatorRegistry(List.of()),
                new SpecialistFinalOutputValidatorRegistry(List.of()),
                new SpecialistDirectOutputProjectorRegistry(List.of()),
                new SpecialistOutputNormalizerRegistry(List.of()),
                schemaValidator,
                new CanonicalJsonSupport(mapper),
                mapper,
                Set.of(),
                properties,
                SpecialistManifestMetrics.noop()
            );

        assertThat(resources.schemas()).hasSize(2);
        assertThat(resources.promptProfiles()).hasSize(1);
        assertThat(bootstrap.status().ready()).isTrue();
        assertThat(bootstrap.registry().listRegistered())
            .singleElement()
            .satisfies(specialist -> {
                assertThat(specialist.id().toString())
                    .isEqualTo("support-knowledge@1");
                assertThat(specialist.source())
                    .isEqualTo(SpecialistDefinitionSource.MANIFEST);
            });
    }
}
