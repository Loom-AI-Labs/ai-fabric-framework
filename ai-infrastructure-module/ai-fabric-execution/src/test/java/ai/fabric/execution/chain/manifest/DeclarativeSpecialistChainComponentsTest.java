package ai.fabric.execution.chain.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistResourceMetadata;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeclarativeSpecialistChainComponentsTest {

    private static final ObjectMapper MAPPER =
        new ObjectMapper().findAndRegisterModules();
    private static final SpecialistJsonSchemaValidator VALIDATOR =
        new SpecialistJsonSchemaValidator();

    @Test
    void mapsOnlyAllowlistedValuesIntoAFreshSchemaValidatedObject()
        throws Exception {
        var mapper = new DeclarativeSpecialistChainTargetInputMapper(
            SpecialistChainComponentId.of("declarative-input", "1"),
            List.of(
                new SpecialistChainManifest.MappingField(
                    SpecialistChainMappingSource.CHAIN_INPUT,
                    "/account/id",
                    "accountId",
                    true
                ),
                new SpecialistChainManifest.MappingField(
                    SpecialistChainMappingSource.MANAGER_OBJECTIVE,
                    null,
                    "objective",
                    true
                )
            ),
            schema(
                "worker-input",
                SpecialistSchemaDirection.INPUT,
                """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["accountId","objective"],
                  "properties":{
                    "accountId":{"type":"string"},
                    "objective":{"type":"string"}
                  }
                }
                """
            ),
            VALIDATOR,
            MAPPER,
            json(bounds(100)),
            SpecialistChainManifestMetrics.noop()
        );
        JsonNode source = MAPPER.readTree(
            "{\"account\":{\"id\":\"acct-7\",\"secret\":\"hidden\"},"
                + "\"ignored\":true}"
        );

        JsonNode mapped = mapper.map(
            source,
            new SpecialistChainTargetRequest(
                "worker@1",
                "Inspect the approved account state."
            )
        );

        assertThat(mapped).isEqualTo(MAPPER.readTree(
            "{\"accountId\":\"acct-7\",\"objective\":"
                + "\"Inspect the approved account state.\"}"
        ));
        assertThat(mapped).isNotSameAs(source);
        assertThat(source.at("/account/secret").asText()).isEqualTo("hidden");
    }

    @Test
    void copiesScalarObjectAndArrayWithoutCoercionOrSourceMutation()
        throws Exception {
        var mapper = new DeclarativeSpecialistChainTargetInputMapper(
            SpecialistChainComponentId.of("typed-input", "1"),
            List.of(
                field("/name", "name"),
                field("/profile", "profile"),
                field("/tags", "tags")
            ),
            schema(
                "typed-worker-input",
                SpecialistSchemaDirection.INPUT,
                """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["name","profile","tags"],
                  "properties":{
                    "name":{"type":"string"},
                    "profile":{"type":"object"},
                    "tags":{"type":"array","items":{"type":"string"}}
                  }
                }
                """
            ),
            VALIDATOR,
            MAPPER,
            json(bounds(100)),
            SpecialistChainManifestMetrics.noop()
        );
        JsonNode source = MAPPER.readTree(
            "{\"name\":\"Ada\",\"profile\":{\"tier\":\"PRO\"},"
                + "\"tags\":[\"stable\",\"verified\"]}"
        );

        JsonNode mapped = mapper.map(
            source,
            new SpecialistChainTargetRequest("worker@1", "Inspect")
        );
        ((com.fasterxml.jackson.databind.node.ObjectNode) mapped.path(
            "profile"
        )).put("tier", "CHANGED");

        assertThat(mapped.path("name").isTextual()).isTrue();
        assertThat(mapped.path("tags").isArray()).isTrue();
        assertThat(source.at("/profile/tier").asText()).isEqualTo("PRO");

        JsonNode wrongType = MAPPER.readTree(
            "{\"name\":17,\"profile\":{},\"tags\":[]}"
        );
        assertThatThrownBy(() -> mapper.map(
            wrongType,
            new SpecialistChainTargetRequest("worker@1", "Inspect")
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("typed-worker-input@1");
    }

    @Test
    void rejectsReservedDestinationsAndCumulativeJsonWork() throws Exception {
        assertThatThrownBy(() -> json(bounds(20)).validateTargetField(
            "__proto__"
        )).isInstanceOf(IllegalArgumentException.class);

        var mapper = new DeclarativeSpecialistChainTargetInputMapper(
            SpecialistChainComponentId.of("bounded-input", "1"),
            List.of(
                field("/one", "one"),
                field("/two", "two")
            ),
            schema(
                "bounded-worker-input",
                SpecialistSchemaDirection.INPUT,
                """
                {"type":"object","additionalProperties":true}
                """
            ),
            VALIDATOR,
            MAPPER,
            json(bounds(5)),
            SpecialistChainManifestMetrics.noop()
        );
        JsonNode source = MAPPER.readTree(
            "{\"one\":{\"a\":\"1\",\"b\":\"2\"},"
                + "\"two\":{\"c\":\"3\",\"d\":\"4\"}}"
        );

        assertThatThrownBy(() -> mapper.map(
            source,
            new SpecialistChainTargetRequest("worker@1", "Inspect")
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cumulative JSON work limit");
    }

    @Test
    void enforcesPointerNodeAndMappedObjectBounds() throws Exception {
        BoundedJsonSupport shallow = json(new SpecialistChainDeclarativeBounds(
            8,
            2,
            2,
            4,
            4,
            2,
            5,
            32,
            48,
            10
        ));

        assertThatThrownBy(() -> shallow.validatePointer(
            "/one/two/three",
            "pointer"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pointer character limit");
        assertThatThrownBy(() -> shallow.select(
            MAPPER.readTree(
                "{\"one\":{\"two\":{\"three\":{\"four\":\"value\"}}}}"
            ),
            "/one",
            true,
            "selected"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("depth limit");
        assertThatThrownBy(() -> shallow.select(
            MAPPER.readTree("{\"value\":\"" + "x".repeat(40) + "\"}"),
            "/value",
            true,
            "selected"
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("byte limit");
        assertThatThrownBy(() -> shallow.validateMappedObject(
            MAPPER.readTree("{\"one\":\"12345678901234567890\","
                + "\"two\":\"12345678901234567890\"}")
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("declarative JSON limit");
    }

    @Test
    void projectsOnlyDeclaredStringsAndApprovedEvidenceIds() throws Exception {
        var projector = new DeclarativeSpecialistChainTargetResultProjector(
            SpecialistChainComponentId.of("declarative-result", "1"),
            schema(
                "worker-output",
                SpecialistSchemaDirection.OUTPUT,
                """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["summary","status","privateReasoning"],
                  "properties":{
                    "summary":{"type":"string"},
                    "status":{"type":"string"},
                    "privateReasoning":{"type":"string"}
                  }
                }
                """
            ),
            VALIDATOR,
            json(bounds(100)),
            "/summary",
            List.of(new SpecialistChainManifest.FactField(
                "status",
                "/status",
                true
            )),
            SpecialistChainEvidencePolicy.NONE,
            SpecialistChainManifestMetrics.noop()
        );
        JsonNode output = MAPPER.readTree(
            "{\"summary\":\"Account is active\",\"status\":\"ACTIVE\","
                + "\"privateReasoning\":\"must not escape\"}"
        );
        Instant now = Instant.parse("2026-09-19T00:00:00Z");

        var projection = projector.project(
            MAPPER.createObjectNode(),
            new AIExecutionResult<>(
                "invocation-1",
                SpecialistId.of("worker", "1"),
                AIExecutionStatus.SUCCEEDED,
                output,
                List.of(),
                Map.of(),
                null,
                now,
                now
            )
        );

        assertThat(projection.summary()).isEqualTo("Account is active");
        assertThat(projection.facts()).containsExactly(
            Map.entry("status", "ACTIVE")
        );
        assertThat(projection.toString()).doesNotContain("privateReasoning");
    }

    @Test
    void projectionFailsClosedForMissingOrNonStringDeclaredValues()
        throws Exception {
        var projector = new DeclarativeSpecialistChainTargetResultProjector(
            SpecialistChainComponentId.of("strict-result", "1"),
            schema(
                "strict-worker-output",
                SpecialistSchemaDirection.OUTPUT,
                """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["summary","status"],
                  "properties":{"summary":{},"status":{}}
                }
                """
            ),
            VALIDATOR,
            json(bounds(100)),
            "/summary",
            List.of(new SpecialistChainManifest.FactField(
                "status",
                "/status",
                true
            )),
            SpecialistChainEvidencePolicy.NONE,
            SpecialistChainManifestMetrics.noop()
        );

        assertThatThrownBy(() -> projector.project(
            MAPPER.createObjectNode(),
            successful(MAPPER.readTree("{\"summary\":\"ok\",\"status\":7}"))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Fact pointers must select strings");
        assertThatThrownBy(() -> projector.project(
            MAPPER.createObjectNode(),
            successful(MAPPER.readTree("{\"summary\":null,\"status\":\"OK\"}"))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not select a required value");
    }

    @Test
    void inputAdapterRejectsNonStringAndOversizedManagerValues()
        throws Exception {
        var adapter = new DeclarativeSpecialistChainInputAdapter(
            SpecialistChainComponentId.of("chain-input", "1"),
            schema(
                "chain-input",
                SpecialistSchemaDirection.INPUT,
                """
                {
                  "type":"object",
                  "additionalProperties":false,
                  "required":["question","account"],
                  "properties":{
                    "question":{},
                    "account":{"type":"string"}
                  }
                }
                """
            ),
            VALIDATOR,
            json(bounds(100)),
            "/question",
            List.of(new SpecialistChainManifest.ContextValue(
                "accountReference",
                "/account",
                true
            ))
        );

        assertThatThrownBy(() -> adapter.currentUserMessage(
            MAPPER.readTree("{\"question\":7,\"account\":\"acct-7\"}")
        )).hasMessageContaining("must select a string");
        assertThatThrownBy(() -> adapter.currentUserMessage(
            MAPPER.createObjectNode()
                .put("question", "x".repeat(4_001))
                .put("account", "acct-7")
        )).hasMessageContaining("bounded non-blank string");
    }

    private static SpecialistChainManifest.MappingField field(
        String pointer,
        String target
    ) {
        return new SpecialistChainManifest.MappingField(
            SpecialistChainMappingSource.CHAIN_INPUT,
            pointer,
            target,
            true
        );
    }

    private static AIExecutionResult<JsonNode> successful(JsonNode output) {
        Instant now = Instant.parse("2026-09-19T00:00:00Z");
        return new AIExecutionResult<>(
            "invocation-strict",
            SpecialistId.of("worker", "1"),
            AIExecutionStatus.SUCCEEDED,
            output,
            List.of(),
            Map.of(),
            null,
            now,
            now
        );
    }

    private static BoundedJsonSupport json(
        SpecialistChainDeclarativeBounds bounds
    ) {
        return new BoundedJsonSupport(
            new CanonicalJsonSupport(MAPPER),
            bounds
        );
    }

    private static SpecialistChainDeclarativeBounds bounds(int work) {
        return new SpecialistChainDeclarativeBounds(
            500,
            16,
            16,
            32,
            128,
            16,
            1_000,
            32_768,
            65_536,
            work
        );
    }

    private static SpecialistSchemaDefinition schema(
        String name,
        SpecialistSchemaDirection direction,
        String json
    ) throws Exception {
        return new SpecialistSchemaDefinition(
            "ai.fabric/v1",
            "SpecialistSchema",
            new SpecialistResourceMetadata(name, "1"),
            new SpecialistSchemaSpec(
                direction,
                "2020-12",
                MAPPER.readTree(json)
            )
        );
    }
}
