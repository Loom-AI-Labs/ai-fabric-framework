package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.config.AIExecutionProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
        assertThat(first.chainManifests()).singleElement().satisfies(chain -> {
            assertThat(chain.manifest().metadata().name())
                .isEqualTo("support-investigation");
            assertThat(chain.resourceHash()).hasSize(64);
            assertThat(chain.source()).isEqualTo("bundle.yml#4");
        });
        assertThat(first.manifests().getFirst().contentHash())
            .isEqualTo(second.manifests().getFirst().contentHash())
            .hasSize(64);
        assertThat(first.manifests().getFirst().source())
            .isEqualTo("bundle.yml#3");
    }

    @Test
    void classifiesSpecialistChainInTheSameOrderedResourcePass()
        throws Exception {
        Path bundle = tempDirectory.resolve("chain.yml");
        Files.writeString(bundle, validChainDocument());

        SpecialistResourceBundle resources = loader.load(properties());

        assertThat(resources.chainManifests()).singleElement().satisfies(
            loaded -> {
                assertThat(loaded.manifest().metadata().name())
                    .isEqualTo("support-investigation");
                assertThat(loaded.resourceHash()).matches("[a-f0-9]{64}");
                assertThat(loaded.source()).isEqualTo("chain.yml#1");
            }
        );
    }

    @Test
    void equivalentYamlAndJsonChainsHaveTheSameCanonicalResourceHash()
        throws Exception {
        Path yamlFile = tempDirectory.resolve("chain.yml");
        Path jsonFile = tempDirectory.resolve("chain.json");
        Files.writeString(yamlFile, validChainDocument());
        JsonNode value = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules()
            .readTree(validChainDocument());
        Files.writeString(
            jsonFile,
            new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(value)
        );

        String yamlHash = loader.load(propertiesFor(yamlFile))
            .chainManifests().getFirst().resourceHash();
        String jsonHash = loader.load(propertiesFor(jsonFile))
            .chainManifests().getFirst().resourceHash();

        assertThat(jsonHash).isEqualTo(yamlHash);
    }

    @Test
    void rejectsJavaComponentReferencesInChainResources() throws Exception {
        Files.writeString(
            tempDirectory.resolve("invalid-chain.yml"),
            validChainDocument().replace(
                "schemaRef: support-chain-input@1",
                "schemaRef: support-chain-input@1\n"
                    + "    adapterRef: applicationAdapter@1"
            )
        );

        assertThatThrownBy(() -> loader.load(properties()))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("MANIFEST_PARSE_FAILED"));
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
    void rejectsChainManifestLargerThanConfiguredManifestBound()
        throws Exception {
        Path manifest = tempDirectory.resolve("large-chain.yml");
        Files.writeString(manifest, validChainDocument());
        AIExecutionProperties.Manifests properties = propertiesFor(manifest);
        properties.setMaxManifestBytes(256);

        assertThatThrownBy(() -> loader.load(properties))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("CHAIN_MANIFEST_TOO_LARGE"));
    }

    @Test
    void rejectsUnsupportedResourceKindsWithoutKeepingPartialState()
        throws Exception {
        Path resource = tempDirectory.resolve("unsupported.yml");
        Files.writeString(
            resource,
            validSchemaDocument()
                + "\n---\n"
                + validChainDocument().replace(
                    "kind: SpecialistChain",
                    "kind: SpecialistGraph"
                )
        );
        AIExecutionProperties.Manifests properties = propertiesFor(resource);
        properties.setFailFast(false);

        SpecialistResourceBundle resources = loader.load(properties);

        assertThat(resources.schemas()).isEmpty();
        assertThat(resources.chainManifests()).isEmpty();
        assertThat(resources.diagnostics()).singleElement().satisfies(
            diagnostic -> assertThat(diagnostic.reason())
                .isEqualTo("RESOURCE_KIND_UNSUPPORTED")
        );
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

    @Test
    void rejectsUnknownDeclarativeChainComponentReferences() throws Exception {
        Files.writeString(
            tempDirectory.resolve("invalid-chain.yml"),
            validChainDocument().replace(
                "conversationPolicy: REQUIRED",
                "conversationPolicy: REQUIRED\n  mapperRef: applicationMapper"
            )
        );

        assertThatThrownBy(() -> loader.load(properties()))
            .isInstanceOf(SpecialistManifestException.class)
            .satisfies(error -> assertThat(
                ((SpecialistManifestException) error).reason()
            ).isEqualTo("MANIFEST_PARSE_FAILED"));
    }

    private AIExecutionProperties.Manifests properties() {
        return propertiesFor(tempDirectory.resolve("*.yml"));
    }

    private AIExecutionProperties.Manifests propertiesFor(Path path) {
        AIExecutionProperties.Manifests properties =
            new AIExecutionProperties.Manifests();
        properties.setEnabled(true);
        properties.setLocations(java.util.List.of(
            "file:" + path.toAbsolutePath()
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
            + validManifestDocument()
            + "\n---\n"
            + validChainDocument();
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

    private String validChainDocument() {
        return """
            apiVersion: ai.fabric/v1
            kind: SpecialistChain
            metadata:
              name: support-investigation
              version: "1"
              displayName: Support Investigation
              description: Coordinates approved support readers.
              labels:
                domain: support
            spec:
              input:
                schemaRef: support-chain-input@1
                managerMessagePointer: /question
                managerContext: []
              manager:
                specialistRef: support-manager@1
              targets:
                - specialistRef: support-reader@1
                  description: Reads approved support facts.
                  input:
                    type: JSON_POINTER_MAP
                    fields:
                      - source: CHAIN_INPUT
                        sourcePointer: /question
                        targetField: question
                      - source: MANAGER_OBJECTIVE
                        targetField: objective
                  result:
                    type: BOUNDED_FACT_PROJECTION
                    summaryPointer: /summary
                    facts:
                      - name: status
                        valuePointer: /status
                    evidenceReferences: ALL_APPROVED
                  transitions:
                    delegationAllowed: true
                    parallelEligible: true
                    handoffAllowed: false
              limits:
                maxDuration: PT30S
                maxManagerDecisions: 3
                maxWorkerInvocations: 1
                maxParallelWorkers: 1
                maxInvocationsPerTarget: 1
                maxProjectedResultCharacters: 4000
              conversationPolicy: REQUIRED
            """;
    }
}
