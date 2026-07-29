package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpecialistRegistryBootstrapTest {

    @Test
    void publishesOneImmutableRegistryWithManifestProvenance() {
        SpecialistRegistryBootstrap bootstrap = bootstrap(
            ManifestTestFixtures.resourceBundle(
                ManifestTestFixtures.manifest()
            ),
            true
        );

        assertThat(bootstrap.registry().listRegistered())
            .singleElement()
            .satisfies(registered -> {
                assertThat(registered.source())
                    .isEqualTo(SpecialistDefinitionSource.MANIFEST);
                assertThat(registered.contentHash())
                    .isEqualTo(ManifestTestFixtures.HASH);
            });
        assertThat(bootstrap.status().ready()).isTrue();
        assertThat(bootstrap.status().manifestDefinitionCount()).isEqualTo(1);
        assertThat(bootstrap.status().javaDefinitionCount()).isZero();
        assertThat(bootstrap.status().registryContentHash()).hasSize(64);
    }

    @Test
    void duplicateManifestIdsFailWithoutSourcePrecedence() {
        SpecialistResourceBundle one = ManifestTestFixtures.resourceBundle(
            ManifestTestFixtures.manifest()
        );
        SpecialistResourceBundle duplicate = new SpecialistResourceBundle(
            List.of(
                one.manifests().getFirst(),
                new LoadedSpecialistManifest(
                    ManifestTestFixtures.manifest(),
                    "b".repeat(64),
                    "duplicate.yml#1"
                )
            ),
            one.schemas(),
            one.promptProfiles()
        );

        assertThatThrownBy(() -> bootstrap(duplicate, true))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("DUPLICATE_SPECIALIST_ID"));
    }

    @Test
    void nonFailFastModeExposesInvalidReadinessAndSkipsDefinition() {
        SpecialistResourceBundle invalid =
            ManifestTestFixtures.resourceBundle(
                ManifestTestFixtures.manifest(
                    "support-knowledge",
                    "missing"
                )
            );

        SpecialistRegistryBootstrap bootstrap = bootstrap(invalid, false);

        assertThat(bootstrap.registry().list()).isEmpty();
        assertThat(bootstrap.status().ready()).isFalse();
        assertThat(bootstrap.status().diagnostics()).singleElement();
    }

    private SpecialistRegistryBootstrap bootstrap(
        SpecialistResourceBundle resources,
        boolean failFast
    ) {
        var mapper = ManifestTestFixtures.objectMapper();
        var schemaValidator = new SpecialistJsonSchemaValidator();
        AIExecutionProperties.Manifests properties =
            new AIExecutionProperties.Manifests();
        properties.setEnabled(true);
        properties.setFailFast(failFast);
        return new SpecialistRegistryBootstrap(
            List.of(),
            resources,
            new DefaultSpecialistManifestCompiler(),
            ManifestTestFixtures.definitionValidator(),
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
    }
}
