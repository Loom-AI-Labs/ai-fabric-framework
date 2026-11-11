package ai.fabric.intent.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionNativeSchemaSupportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void acceptsEmptyNativeSchema() {
        ActionNativeSchemaSupport.validateDefinition(Map.of());
        ActionNativeSchemaSupport.validateInput(Map.of(), Map.of("value", 2));
        assertThat(ActionNativeSchemaSupport.snapshot(Map.of())).isEmpty();
    }

    @Test
    void acceptsOmittedRootTypeAndPreservesRootUnionForObjectArguments() throws Exception {
        var schema = read("""
            {"oneOf":[
               {"properties":{"left":{"type":"number","minimum":0.25}},"required":["left"],"additionalProperties":false},
               {"properties":{"right":{"type":["string","null"]}},"required":["right"],"additionalProperties":false}]}
            """);
        ActionNativeSchemaSupport.validateInput(schema, Map.of("left", 0.5));
        ActionNativeSchemaSupport.validateInput(schema, java.util.Collections.singletonMap("right", null));
        assertThat(ActionNativeSchemaSupport.publicInputSchema(AIActionMetaData.builder().inputSchema(schema).build()))
            .isEqualTo(schema);
        ActionNativeSchemaSupport.validateInput(Map.of("type", List.of("object", "null")), Map.of());
    }

    @Test
    void validatesNestedUnionNullableNumericEnumAndDecimalBounds() throws Exception {
        Map<String, Object> schema = read("""
            {"type":"object","properties":{"selection":{"$ref":"#/$defs/selection"}},
             "required":["selection"],"additionalProperties":false,
             "$defs":{"selection":{"oneOf":[
               {"type":"null"},
               {"type":"object","properties":{
                  "price":{"type":"number","exclusiveMinimum":0.25,"maximum":2.75},
                  "tier":{"enum":[1,2]},"note":{"type":["string","null"]}},
                "required":["price","tier","note"],"additionalProperties":false}]}}}
            """);
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("selection", null);
        assertThatCode(() -> ActionNativeSchemaSupport.validateInput(schema, nullable)).doesNotThrowAnyException();
        Map<String, Object> selected = read("{\"selection\":{\"price\":0.5,\"tier\":2,\"note\":null}}");
        assertThatCode(() -> ActionNativeSchemaSupport.validateInput(schema, selected)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ActionNativeSchemaSupport.validateInput(schema,
            read("{\"selection\":{\"price\":0.25,\"tier\":2,\"note\":null}}")))
            .isInstanceOf(ActionParameterContractValidator.ActionParameterContractException.class);
        assertThatThrownBy(() -> ActionNativeSchemaSupport.validateInput(schema,
            read("{\"selection\":{\"price\":1,\"tier\":\"2\",\"note\":null}}")))
            .isInstanceOf(ActionParameterContractValidator.ActionParameterContractException.class);
    }

    @Test
    void rejectsMissingRequiredAndUnknownFieldsWithoutEchoingValues() {
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("id", Map.of("type", "string")),
            "required", List.of("id"), "additionalProperties", false);
        assertThatThrownBy(() -> ActionNativeSchemaSupport.validateInput(schema, Map.of()))
            .isInstanceOf(ActionParameterContractValidator.ActionParameterContractException.class);
        assertThatThrownBy(() -> ActionNativeSchemaSupport.validateInput(schema, Map.of("id", "ok", "secret", "sensitive-value")))
            .hasMessageNotContaining("sensitive-value");
    }

    @Test
    void rejectsExternalReferencesIncludingDynamicAndUnusedDefinitions() {
        for (String keyword : List.of("$ref", "$dynamicRef", "$recursiveRef")) {
            for (String reference : List.of("https://example.org/schema", "file:///etc/passwd", "relative.json")) {
                assertThatThrownBy(() -> ActionNativeSchemaSupport.validateDefinition(
                    Map.of("$defs", Map.of("unused", Map.of(keyword, reference)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("local fragment");
            }
        }
    }

    @Test
    void refusesCustomMetaSchemaAndInvalidSchema() {
        assertThatThrownBy(() -> ActionNativeSchemaSupport.validateDefinition(
            Map.of("$schema", "https://example.invalid/meta", "type", "object")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionNativeSchemaSupport.validateDefinition(Map.of("type", "not-a-json-type")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverFetchesExternalMetaSchemas() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/schema", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> ActionNativeSchemaSupport.validateDefinition(Map.of(
                "$schema", "http://127.0.0.1:" + server.getAddress().getPort() + "/schema", "type", "object")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void onlyQualifiedDialectsCanBeResolvedAndAllResourceLoadersAreCleared() {
        var registry = (com.networknt.schema.SchemaRegistry) org.springframework.test.util.ReflectionTestUtils
            .getField(ActionNativeSchemaSupport.class, "REGISTRY");
        assertThat(registry.getSchemaLoader().getResourceLoaders()).isEmpty();
        for (String dialect : List.of("file:///tmp/native-meta-schema.json", "classpath:application.yml",
            "resource:application.yml", "https://example.invalid/schema", "https://json-schema.org/draft/2019-09/schema")) {
            assertThatThrownBy(() -> ActionNativeSchemaSupport.validateDefinition(Map.of("$schema", dialect)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only qualified");
            assertThatThrownBy(() -> ActionNativeSchemaSupport.validateDefinition(Map.of(
                "type", "object", "properties", Map.of("value", Map.of("$schema", dialect)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only qualified");
        }
    }

    @Test
    void draft07And202012UseBundledMetaSchemasWithoutChangingOriginalDialect() {
        for (String dialect : List.of("http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft-07/schema",
            "https://json-schema.org/draft/2020-12/schema", "https://json-schema.org/draft/2020-12/schema#")) {
            var schema = Map.<String, Object>of("$schema", dialect, "type", "object", "properties", Map.of(
                "value", Map.of("type", List.of("number", "null"), "minimum", 0.25)), "required", List.of("value"));
            ActionNativeSchemaSupport.validateInput(schema, Map.of("value", 0.5));
            ActionNativeSchemaSupport.validateInput(schema, java.util.Collections.singletonMap("value", null));
            assertThat(schema).containsEntry("$schema", dialect);
        }
    }

    @Test
    void nativeContractWinsOverLossySimplifiedTypesAndKeepsDraftCollectionPossible() {
        AIActionMetaData metadata = AIActionMetaData.builder().name("native")
            .inputSchema(Map.of("type", "object", "properties", Map.of("value", Map.of("type", List.of("string", "null"))),
                "required", List.of("value"), "additionalProperties", false))
            .parameterSchemas(Map.of("value", AIActionParamSchema.builder().type(AIActionParamType.INTEGER).required(true).build()))
            .build();
        assertThatCode(() -> ActionParameterContractValidator.validateProvided(metadata, Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> ActionParameterContractValidator.validateExecutable(metadata,
            java.util.Collections.singletonMap("value", null))).doesNotThrowAnyException();
        assertThatCode(() -> ActionParameterContractValidator.validateExecutable(metadata, Map.of("value", "valid"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ActionParameterContractValidator.validateExecutable(metadata, Map.of("value", 3)))
            .isInstanceOf(ActionParameterContractValidator.ActionParameterContractException.class);
    }

    @Test
    void publicProjectionRemovesTrustedFieldsButKeepsNativeShapesAndOriginal() {
        Map<String, Object> input = Map.of("type", "object", "properties", Map.of(
            "query", Map.of("anyOf", List.of(Map.of("type", "string"), Map.of("type", "null"))),
            "tenantId", Map.of("type", "string", "description", "internal-only")), "required", List.of("query", "tenantId"));
        AIActionMetaData metadata = AIActionMetaData.builder().inputSchema(input)
            .parameterSchemas(Map.of("tenantId", AIActionParamSchema.builder().visibility("INTERNAL").askUser(false).build())).build();
        Map<String, Object> projected = ActionNativeSchemaSupport.publicInputSchema(metadata);
        assertThat(ActionNativeSchemaSupport.json(projected)).contains("anyOf").doesNotContain("tenantId", "internal-only");
        assertThat(projected.get("required")).isEqualTo(List.of("query"));
        assertThat(input.get("required")).isEqualTo(List.of("query", "tenantId"));
    }

    @Test
    void snapshotPreservesNullEnumsAndDoesNotShareMutableSchemas() throws Exception {
        Map<String, Object> original = read("{\"properties\":{\"value\":{\"enum\":[1,null]}}}");
        Map<String, Object> snapshot = ActionNativeSchemaSupport.snapshot(original);
        ((Map<?, ?>) original.get("properties")).clear();
        assertThat(ActionNativeSchemaSupport.json(snapshot)).contains("[1,null]");
        assertThatThrownBy(() -> ((Map<?, ?>) snapshot.get("properties")).clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void trustedRootBindingDoesNotStripSameNamedNestedDefinitionProperty() throws Exception {
        var input = read("""
            {"type":"object","properties":{
              "scopeKey":{"type":"string"},"selection":{"$ref":"#/$defs/selection"}},
             "required":["scopeKey","selection"],
             "$defs":{"selection":{"type":"object","properties":{"scopeKey":{"type":"number"}},"required":["scopeKey"]}}}
            """);
        var metadata = AIActionMetaData.builder().inputSchema(input).parameterSchemas(Map.of("scopeKey",
            AIActionParamSchema.builder().visibility("INTERNAL").askUser(false).build())).build();
        var projected = ActionNativeSchemaSupport.publicInputSchema(metadata);
        assertThat(projected.get("required")).isEqualTo(List.of("selection"));
        assertThat(projected.get("$defs")).isEqualTo(input.get("$defs"));
        ActionNativeSchemaSupport.validateInput(projected, Map.of("selection", Map.of("scopeKey", 3)));
    }

    @Test
    void referenceLookingDataInConstAndExamplesIsNotASchemaReference() {
        var literal = Map.of("$ref", "https://business.example/document");
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of("payload", Map.of(
            "const", literal, "examples", List.of(literal))));
        ActionNativeSchemaSupport.validateInput(schema, Map.of("payload", literal));
    }

    private static Map<String, Object> read(String text) throws Exception {
        return JSON.readValue(text, new TypeReference<>() {});
    }
}
