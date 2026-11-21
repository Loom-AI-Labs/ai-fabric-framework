package ai.fabric.intent.action.connector;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorAutomaticEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void arbitraryNamedArraysAndNestedValuesReachTheModelWithoutAProjection() throws Exception {
        var original = Map.<String, Object>of("products", List.of(Map.of("id", "desk-1", "price", 499,
            "details", Map.of("adjustable", true, "dimensions", List.of(80, 120)))));
        var facts = handler(null).buildPostActionLlmFacts(success(original), null).orElseThrow();
        assertThat(facts.get("data")).isEqualTo(original);
        assertThat(mapper.readTree(mapper.writeValueAsString(facts)).at("/data/products/0/details/dimensions/1").asInt()).isEqualTo(120);
    }

    @Test void automaticModeIgnoresStoredSelectionWithoutDeletingIt() {
        var selected = new ConnectorActionLlmFactsDefinition(null, List.of("id"), List.of(), List.of(),
            ConnectorActionLlmFactsDefinition.Mode.AUTOMATIC);
        var facts = handler(selected).buildPostActionLlmFacts(success(Map.of("id", "1", "price", 499)), null).orElseThrow();
        assertThat(facts.get("data")).isEqualTo(Map.of("id", "1", "price", 499));
        assertThat(selected.copyFields()).containsExactly("id");
    }

    @Test void selectedFieldsRemainExclusiveAndAnEmptySelectionDoesNotWiden() {
        var rules = new ConnectorActionLlmFactsDefinition(null, List.of("id"), List.of(), List.of());
        var facts = handler(rules).buildPostActionLlmFacts(success(Map.of("id", "1", "privateBusinessField", "not-selected")), null).orElseThrow();
        assertThat(facts).containsEntry("id", "1").doesNotContainKeys("data", "privateBusinessField");
        var empty = new ConnectorActionLlmFactsDefinition(null, List.of(), List.of(), List.of());
        assertThat(handler(empty).buildPostActionLlmFacts(success(Map.of("privateBusinessField", "not-selected")), null).orElseThrow())
            .doesNotContainKeys("data", "privateBusinessField");
    }

    @Test void failedActionDataIsNotAutomaticallyDisclosed() {
        var failed = ActionResult.builder().success(false).errorCode("DENIED")
            .data(ActionPayload.object(Map.of("debugBody", "must-not-be-forwarded"))).build();
        var facts = handler(null).buildPostActionLlmFacts(failed, null).orElseThrow();
        assertThat(facts).containsEntry("success", false).doesNotContainKeys("data", "debugBody");
    }

    @Test void credentialsAndDeclaredSensitiveFieldsAreExcludedAtEveryDepth() throws Exception {
        var input = Map.<String, Object>of("Authorization", "bearer-secret", "request_headers", Map.of("a", "private-header"),
            "products", List.of(Map.of("name", "Desk", "api_key", "private-key", "customerSecret", "private-customer-value")));
        var result = ConnectorAutomaticEvidence.project(input, Set.of("customerSecret"));
        String json = mapper.writeValueAsString(result.data());
        assertThat(json).contains("Desk").doesNotContain("bearer-secret", "private-header", "private-key", "private-customer-value");
        assertThat(result.truncated()).isFalse();
    }

    @Test void wideDeepAndLongOutputIsBoundedWithVisibleTruncation() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) {
            var row = new LinkedHashMap<String, Object>();
            for (int j = 0; j < 100; j++) row.put("field-" + j, "\"\\\n".repeat(5000));
            rows.add(row);
        }
        var result = ConnectorAutomaticEvidence.project(Map.of("largeCollection", rows), Set.of());
        assertThat(result.truncated()).isTrue();
        assertThat(mapper.writeValueAsString(result.data()).length()).isLessThanOrEqualTo(ConnectorAutomaticEvidence.MAX_CHARACTERS);
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);
        assertThat(ConnectorAutomaticEvidence.project(cycle, Set.of()).truncated()).isTrue();
    }

    @Test void unknownObjectsAreNotStringifiedAndInputIsNotMutated() {
        var input = new LinkedHashMap<String, Object>();
        input.put("extension", new Object() { @Override public String toString() { throw new AssertionError("Not JSON"); } });
        var result = ConnectorAutomaticEvidence.project(input, Set.of());
        assertThat(result.truncated()).isTrue();
        assertThat(input).containsKey("extension");
    }

    private ActionResult success(Map<String, Object> data) {
        return ActionResult.builder().success(true).data(ActionPayload.object(data)).build();
    }

    private ConnectorAIActionHandler handler(ConnectorActionLlmFactsDefinition config) {
        return new ConnectorAIActionHandler(AIActionMetaData.builder().name("lookup_record").category("READ")
            .accessMode(ActionAccessMode.READ).build(), false, null, Set.of(), null, config);
    }
}
