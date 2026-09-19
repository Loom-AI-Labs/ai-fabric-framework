package dev.aifabric.examples.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.chain.manifest.SpecialistChainInputMappingType;
import ai.fabric.execution.chain.manifest.SpecialistChainResultProjectionType;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.specialist.manifest.DefaultSpecialistManifestLoader;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistResourceMetadata;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicDeclarativeChainResourceTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules();

    @Test
    void publicArtifactLoadsAndValidatesAChainWithoutJavaComponentReferences()
        throws Exception {
        AIExecutionProperties.Manifests properties =
            new AIExecutionProperties.Manifests();
        properties.setEnabled(true);
        properties.setLocations(java.util.List.of(
            "classpath:ai-specialists/declarative-support-chain.yml"
        ));

        var resources = new DefaultSpecialistManifestLoader(objectMapper)
            .load(properties);

        assertThat(resources.schemas()).singleElement();
        assertThat(resources.chainManifests()).singleElement().satisfies(
            loaded -> {
                assertThat(loaded.manifest().metadata().name())
                    .isEqualTo("consumer-declarative-support");
                assertThat(loaded.manifest().spec().targets()).hasSize(2);
                assertThat(loaded.manifest().spec().targets())
                    .allSatisfy(target -> {
                        assertThat(target.input().type()).isEqualTo(
                            SpecialistChainInputMappingType.JSON_POINTER_MAP
                        );
                        assertThat(target.result().type()).isEqualTo(
                            SpecialistChainResultProjectionType
                                .BOUNDED_FACT_PROJECTION
                        );
                    });
                assertThat(loaded.resourceHash()).matches("[a-f0-9]{64}");
                assertThat(loaded.manifest().toString()).doesNotContain(
                    "beanRef",
                    "className",
                    "mapperRef",
                    "projectorRef"
                );
            }
        );

        List<JsonNode> documents;
        try (InputStream input = resource(
            "/ai-specialists/declarative-support-chain.yml"
        )) {
            documents = yaml().readerFor(JsonNode.class)
                .<JsonNode>readValues(input)
                .readAll();
        }

        var validator = new SpecialistJsonSchemaValidator();
        var resourceSchema = publishedResourceSchema();
        documents.forEach(document -> validator.validate(
            resourceSchema,
            document
        ));
        assertThat(documents)
            .extracting(document -> document.path("kind").asText())
            .containsExactly("SpecialistSchema", "SpecialistChain");
    }

    private SpecialistSchemaDefinition publishedResourceSchema()
        throws Exception {
        try (InputStream input = resource(
            "/META-INF/ai-fabric/specialist-resource-v1.schema.json"
        )) {
            return new SpecialistSchemaDefinition(
                "ai.fabric/v1",
                "SpecialistSchema",
                new SpecialistResourceMetadata(
                    "specialist-resource-v1",
                    "1"
                ),
                new SpecialistSchemaSpec(
                    SpecialistSchemaDirection.INPUT,
                    "2020-12",
                    objectMapper.readTree(input)
                )
            );
        }
    }

    private ObjectMapper yaml() {
        return new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    }

    private InputStream resource(String path) {
        InputStream input = getClass().getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException("Missing test resource " + path);
        }
        return input;
    }
}
