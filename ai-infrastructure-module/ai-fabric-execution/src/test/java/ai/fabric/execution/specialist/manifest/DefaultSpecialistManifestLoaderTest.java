package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.config.AIExecutionProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultSpecialistManifestLoaderTest {

    @TempDir
    Path tempDirectory;

    private final DefaultSpecialistManifestLoader loader =
        new DefaultSpecialistManifestLoader(
            ManifestTestFixtures.objectMapper()
        );

    @Test
    void loadsMultipleStrictResourcesWithStableCanonicalHash()
        throws Exception {
        Path bundle = tempDirectory.resolve("bundle.yml");
        Files.writeString(bundle, validBundle());
        AIExecutionProperties.Manifests properties = properties();

        SpecialistResourceBundle first = loader.load(properties);
        SpecialistResourceBundle second = loader.load(properties);

        assertThat(first.schemas()).hasSize(1);
        assertThat(first.promptProfiles()).hasSize(1);
        assertThat(first.manifests()).hasSize(1);
        assertThat(first.manifests().getFirst().contentHash())
            .isEqualTo(second.manifests().getFirst().contentHash())
            .hasSize(64);
        assertThat(first.manifests().getFirst().source())
            .isEqualTo("bundle.yml#3");
    }

    @Test
    void rejectsUnknownFieldsAndDoesNotKeepPartialResourceState()
        throws Exception {
        Files.writeString(
            tempDirectory.resolve("invalid.yml"),
            validSchemaDocument()
                + "\n---\n"
                + validManifestDocument()
                + "\n  unknownField: forbidden\n"
        );
        AIExecutionProperties.Manifests properties = properties();
        properties.setFailFast(false);

        SpecialistResourceBundle resources = loader.load(properties);

        assertThat(resources.schemas()).isEmpty();
        assertThat(resources.manifests()).isEmpty();
        assertThat(resources.diagnostics()).singleElement().satisfies(
            diagnostic -> assertThat(diagnostic.reason())
                .isEqualTo("MANIFEST_PARSE_FAILED")
        );
    }

    @Test
    void rejectsResourceLargerThanConfiguredBound() throws Exception {
        Files.writeString(
            tempDirectory.resolve("large.yml"),
            validManifestDocument() + "#".repeat(2_000)
        );
        AIExecutionProperties.Manifests properties = properties();
        properties.setMaxResourceBytes(256);

        assertThatThrownBy(() -> loader.load(properties))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("RESOURCE_TOO_LARGE"));
    }

    @Test
    void rejectsInvalidExactResourceIdentifiers() throws Exception {
        Files.writeString(
            tempDirectory.resolve("invalid-id.yml"),
            validSchemaDocument().replace(
                "name: support-question",
                "name: Support_Question"
            )
        );

        assertThatThrownBy(() -> loader.load(properties()))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("SCHEMA_ID_INVALID"));
    }

    @Test
    void rejectsUnknownInteractionCapability() throws Exception {
        Files.writeString(
            tempDirectory.resolve("invalid-capability.yml"),
            validManifestDocument().replace(
                "recordValidatedTurns: true",
                "recordValidatedTurns: true\n"
                    + "                interactionCapability: INVENTED"
            )
        );

        assertThatThrownBy(() -> loader.load(properties()))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("MANIFEST_PARSE_FAILED"));
    }

    private AIExecutionProperties.Manifests properties() {
        AIExecutionProperties.Manifests properties =
            new AIExecutionProperties.Manifests();
        properties.setEnabled(true);
        properties.setLocations(java.util.List.of(
            "file:" + tempDirectory.toAbsolutePath() + "/*.yml"
        ));
        return properties;
    }

    private String validBundle() {
        return validSchemaDocument()
            + "\n---\n"
            + """
            apiVersion: ai.fabric/v1
            kind: SpecialistPromptProfile
            metadata:
              name: grounded-support
              version: "1"
            spec:
              constraints: Use only approved evidence.
              outputContract: Return one answer.
            """
            + "\n---\n"
            + validManifestDocument();
    }

    private String validSchemaDocument() {
        return """
            apiVersion: ai.fabric/v1
            kind: SpecialistSchema
            metadata:
              name: support-question
              version: "1"
            spec:
              direction: INPUT
              draft: "2020-12"
              schema:
                type: object
                additionalProperties: false
                required: [question]
                properties:
                  question:
                    type: string
            """;
    }

    private String validManifestDocument() {
        return """
            apiVersion: ai.fabric/v1
            kind: Specialist
            metadata:
              name: support-knowledge
              version: "1"
              displayName: Support Knowledge
              description: Approved support answers.
            spec:
              mode: deep
              instructions:
                objective: Answer from evidence.
                promptProfileRef: grounded-support@1
              execution:
                strategy: SINGLE_PASS
                writePolicy: DISABLED
              capabilities:
                retrieval:
                  enabled: true
                  vectorSpaces: [support-policy]
                actions:
                  visible: []
                  requestableReads: []
                  proposableWrites: []
              input:
                schemaRef: support-question@1
                rendering: PRIMARY_TEXT_WITH_JSON_CONTEXT
                primaryTextPointer: /question
                conversationTextPointer: /question
                contextPointers: []
                context:
                  position: support
              grounding:
                requirement: REQUIRED
                requireEvidenceCitations: true
                sources:
                  - type: ANY_ALLOWED_VECTOR_SPACE
                    minimumCount: 1
                    requiredEvidenceIds: []
                    groundingUsable: false
                validatorRefs: []
              output:
                mode: STRUCTURED_GENERATION
                schemaRef: support-answer@1
                conversationTextPointer: /answer
                finalValidatorRefs: []
              conversation:
                binding: OPTIONAL
                recordValidatedTurns: true
              limits:
                maxDuration: PT30S
                maxInputCharacters: 4000
                maxGroundingCharacters: 12000
                maxEvidenceReferences: 10
                maxOutputCharacters: 8000
                maxOutputTokens: 700
            """;
    }
}
