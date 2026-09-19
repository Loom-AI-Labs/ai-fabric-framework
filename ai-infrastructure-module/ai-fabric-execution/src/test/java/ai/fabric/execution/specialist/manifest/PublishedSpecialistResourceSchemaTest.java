package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublishedSpecialistResourceSchemaTest {

    private final ObjectMapper json = new ObjectMapper()
        .findAndRegisterModules();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
        .findAndRegisterModules();
    private final SpecialistJsonSchemaValidator validator =
        new SpecialistJsonSchemaValidator();

    @Test
    void publishedSchemaValidatesSpecialistsAndDeclarativeChains()
        throws Exception {
        SpecialistSchemaDefinition schema = publishedSchema();
        validator.validateDefinition(schema, "published-schema");

        List<JsonNode> resources = new ArrayList<>();
        resources.addAll(documents(
            "/META-INF/ai-fabric/examples/support-knowledge-specialist.yml"
        ));
        resources.add(yaml.readTree(chainManifest()));

        assertThat(resources).hasSize(5);
        resources.forEach(resource -> validator.validate(schema, resource));
    }

    @Test
    void publishedSchemaRejectsExecutableJavaReferences() throws Exception {
        SpecialistSchemaDefinition schema = publishedSchema();
        JsonNode chain = yaml.readTree(chainManifest()).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) chain.path("spec"))
            .put("inputAdapterRef", "unsafe-bean-name");

        assertThatThrownBy(() -> validator.validate(schema, chain))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("specialist-resource-v1@1");
    }

    private SpecialistSchemaDefinition publishedSchema() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
            "/META-INF/ai-fabric/specialist-resource-v1.schema.json"
        )) {
            if (input == null) {
                throw new IllegalStateException(
                    "Published specialist resource schema is missing"
                );
            }
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
                    json.readTree(input)
                )
            );
        }
    }

    private List<JsonNode> documents(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                    "Test resource is missing: " + resource
                );
            }
            MappingIterator<JsonNode> iterator = yaml.readerFor(JsonNode.class)
                .readValues(input);
            List<JsonNode> values = new ArrayList<>();
            while (iterator.hasNextValue()) {
                JsonNode value = iterator.nextValue();
                if (value != null && !value.isNull()) {
                    values.add(value);
                }
            }
            return values;
        }
    }

    private String chainManifest() {
        return """
            apiVersion: ai.fabric/v1
            kind: SpecialistChain
            metadata:
              name: published-chain-proof
              version: "1"
              displayName: Published Chain Proof
              description: Validates the public declarative chain contract.
              labels: {domain: support}
            spec:
              input:
                schemaRef: support-chain-input@1
                managerMessagePointer: /question
                managerContext: []
              manager:
                specialistRef: support-chain-manager@1
              targets:
                - specialistRef: support-reader@1
                  description: Reads approved support evidence.
                  input:
                    type: JSON_POINTER_MAP
                    fields:
                      - source: CHAIN_INPUT
                        sourcePointer: /question
                        targetField: question
                        required: true
                  result:
                    type: BOUNDED_FACT_PROJECTION
                    summaryPointer: /summary
                    facts: []
                    evidenceReferences: ALL_APPROVED
                  transitions:
                    delegationAllowed: true
                    parallelEligible: false
                    handoffAllowed: false
              limits:
                maxDuration: PT20S
                maxManagerDecisions: 2
                maxWorkerInvocations: 1
                maxParallelWorkers: 1
                maxInvocationsPerTarget: 1
                maxProjectedResultCharacters: 2000
              conversationPolicy: DISABLED
            """;
    }
}
