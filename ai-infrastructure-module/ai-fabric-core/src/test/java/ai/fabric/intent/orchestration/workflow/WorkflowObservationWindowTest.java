package ai.fabric.intent.orchestration.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowObservationWindowTest {
    final ObjectMapper mapper = new ObjectMapper();

    @Test void latest24KSourceChunkKeepsEveryCharacterAndItsActualNextOffset() {
        String chunk = "BEGIN:" + "m".repeat(23984) + ":MIDDLEEND";
        var checkpoint = checkpoint(Map.of("text", chunk, "nextOffset", 24000, "hasMore", true));
        JsonNode latest = window(checkpoint).path("items").get(0);
        assertThat(latest.path("resultTruncated").asBoolean()).isFalse();
        assertThat(latest.path("result").path("text").asText()).isEqualTo(chunk);
        assertThat(latest.path("result").path("nextOffset").asInt()).isEqualTo(24000);
        assertThat(latest.path("result").has("fields")).isFalse();
        assertThat(checkpoint.steps().getFirst().observation().get("text")).isEqualTo(chunk);
    }

    @Test void latestContractStringAndRequiredDeepMappingFieldsRetainOriginalShape() {
        var contract = mapper.createObjectNode();
        contract.put("contract", "definition:" + "c".repeat(24000));
        for (int i = 0; i < 64; i++) contract.put("mappingField" + i, "required" + i);
        var mapping = contract.putObject("requiredMapping");
        for (int i = 0; i < 12; i++) mapping = mapping.putObject("level" + i);
        mapping.put("required", true).put("sourcePath", "$.data.operation.id").put("targetPath", "receipt.id");
        var checkpoint = checkpoint(mapper.convertValue(contract, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        JsonNode latest = window(checkpoint).path("items").get(0);
        assertThat(latest.path("resultTruncated").asBoolean()).isFalse();
        assertThat(latest.path("result")).isEqualTo(contract);
    }

    @Test void oversizedLatestResultHasExplicitTruncationAndDurableReceiptReference() {
        var checkpoint = checkpoint(Map.of("text", "x".repeat(100000), "nextOffset", 100000));
        JsonNode latest = window(checkpoint).path("items").get(0);
        assertThat(latest.path("resultTruncated").asBoolean()).isTrue();
        assertThat(latest.path("receiptRef").asText()).isEqualTo("work:step:1");
        assertThat(latest.path("originalResultChars").asInt()).isGreaterThan(100000);
        assertThat(latest.path("result").toString()).contains("truncated").hasSizeLessThanOrEqualTo(48000);
        assertThat(checkpoint.steps().getFirst().observation().get("text").toString()).hasSize(100000);
    }

    @Test void olderSummariesAreRemovedBeforeLatestContentToFitActualEncodedBudget() throws Exception {
        var checkpoint = checkpoint(Map.of("text", "x".repeat(24000), "nextOffset", 24000));
        var steps = new ArrayList<Step>();
        for (int i = 0; i < 20; i++) steps.add(new Step("work:old:" + i, "read", false, Map.of(), "hash",
            Effect.EXECUTED, Map.of("text", "old".repeat(8000)), null));
        steps.add(checkpoint.steps().getFirst());
        checkpoint = new WorkflowCheckpoint(checkpoint.workId(), checkpoint.requestFingerprint(), checkpoint.authorityFingerprint(),
            checkpoint.definitionFingerprint(), checkpoint.deadline(), 1, checkpoint.usage(), steps, State.RUNNING, null, List.of(), null);
        var projection = WorkflowObservationWindow.project(checkpoint, mapper, 30000);
        JsonNode window = mapper.valueToTree(projection);
        var latest = window.path("items").get(window.path("items").size() - 1);
        assertThat(mapper.writeValueAsString(projection)).hasSizeLessThanOrEqualTo(30000);
        assertThat(window.path("hasMore").asBoolean()).isTrue();
        assertThat(window.path("totalStepCount").asInt()).isEqualTo(21);
        assertThat(latest.path("resultTruncated").asBoolean()).isFalse();
        assertThat(latest.path("result").path("text").asText()).isEqualTo("x".repeat(24000));
        assertThat(checkpoint.steps()).hasSize(21);
    }

    @Test void escapingAndExcessDepthRemainExplicitlyBounded() throws Exception {
        var checkpoint = checkpoint(Map.of("text", "\n\"\\".repeat(24000)));
        var projection = WorkflowObservationWindow.project(checkpoint, mapper, 10000);
        assertThat(mapper.writeValueAsString(projection)).hasSizeLessThanOrEqualTo(10000).contains("resultTruncated\":true", "receiptRef");
        var deep = mapper.createObjectNode(); var level = deep;
        for (int i = 0; i < 20; i++) level = level.putObject("level" + i);
        level.put("value", "required");
        checkpoint = checkpoint(mapper.convertValue(deep, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        assertThat(window(checkpoint).path("items").get(0).path("resultTruncated").asBoolean()).isTrue();
    }

    JsonNode window(WorkflowCheckpoint checkpoint) { return mapper.valueToTree(WorkflowObservationWindow.project(checkpoint, mapper)); }

    WorkflowCheckpoint checkpoint(Map<String, Object> observation) {
        return new WorkflowCheckpoint("work", "request", "authority", "definition", BoundedWorkflowRunnerTest.NOW.plusSeconds(3600),
            1, new WorkflowAllowance.Usage(1, 0, 1, 0), List.of(new Step("work:step:1", "read", false, Map.of(), "hash",
                Effect.EXECUTED, observation, null)), State.RUNNING, null, List.of(), null);
    }
}
