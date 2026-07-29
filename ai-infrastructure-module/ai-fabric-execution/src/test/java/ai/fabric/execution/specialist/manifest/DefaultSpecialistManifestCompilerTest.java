package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class DefaultSpecialistManifestCompilerTest {

    private final DefaultSpecialistManifestCompiler compiler =
        new DefaultSpecialistManifestCompiler();

    @Test
    void compilesConfigurationOnlySpecialistIntoExistingDefinitionPath() {
        SpecialistCompilationResult result = compiler.compile(
            ManifestTestFixtures.manifest(),
            ManifestTestFixtures.compilationContext()
        );

        assertThat(result.specialist().source())
            .isEqualTo(SpecialistDefinitionSource.MANIFEST);
        assertThat(result.specialist().contentHash())
            .isEqualTo(ManifestTestFixtures.HASH);
        assertThat(result.specialist().definition().id().toString())
            .isEqualTo("support-knowledge@1");
        assertThat(result.specialist().definition().inputAdapter().inputType())
            .isEqualTo(JsonNode.class);
        assertThat(result.specialist().definition().outputAdapter().outputMode())
            .isEqualTo(SpecialistOutputMode.STRUCTURED_GENERATION);
        assertThat(result.specialist().definition()
            .outputAdapter().outputContract())
            .isInstanceOf(JsonSchemaOutputContract.class);
    }

    @Test
    void unknownModeFailsWithStableSafeReason() {
        assertThatThrownBy(() -> compiler.compile(
            ManifestTestFixtures.manifest("support-knowledge", "missing"),
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("MANIFEST_COMPILATION_FAILED"))
            .hasMessageContaining("unknown Mode");
    }

    @Test
    void unsupportedApiVersionFailsBeforeRegistration() {
        SpecialistManifest valid = ManifestTestFixtures.manifest();
        SpecialistManifest unsupported = new SpecialistManifest(
            "ai.fabric/v2",
            valid.kind(),
            valid.metadata(),
            valid.spec()
        );

        assertThatThrownBy(() -> compiler.compile(
            unsupported,
            ManifestTestFixtures.compilationContext()
        ))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("RESOURCE_API_VERSION_UNSUPPORTED"));
    }
}
