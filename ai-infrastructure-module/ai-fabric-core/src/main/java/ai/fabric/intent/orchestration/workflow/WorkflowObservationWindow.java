package ai.fabric.intent.orchestration.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** A prompt window only. Truncation never removes authoritative journal entries. */
public final class WorkflowObservationWindow {
    private WorkflowObservationWindow() {}

    public static Map<String, Object> project(WorkflowCheckpoint checkpoint, ObjectMapper mapper) {
        int offset = Math.max(0, checkpoint.steps().size() - 8);
        var items = new ArrayList<Map<String, Object>>();
        for (var step : checkpoint.steps().subList(offset, checkpoint.steps().size())) {
            var value = new LinkedHashMap<String, Object>();
            value.put("operationId", step.operationId()); value.put("action", step.action()); value.put("effect", step.effect());
            value.put("result", bounded(mapper.valueToTree(step.observation()), new int[] {4000}, 0));
            items.add(value);
        }
        return Map.of("items", items, "stepOffset", offset, "totalStepCount", checkpoint.steps().size(),
            "hasMore", offset > 0, "details", "Read omitted receipt/state details through authorized scoped tools; absence from this window is not completion.");
    }

    private static Object bounded(JsonNode node, int[] remaining, int depth) {
        if (remaining[0] <= 0 || depth > 6) return Map.of("truncated", true, "kind", node.getNodeType().name());
        if (node.isTextual()) {
            String text = node.textValue();
            int size = Math.min(text.length(), Math.min(remaining[0], 1500));
            remaining[0] -= size;
            return size == text.length() ? text : Map.of("value", text.substring(0, size), "truncated", true, "originalLength", text.length());
        }
        if (node.isObject()) {
            var fields = new LinkedHashMap<String, Object>();
            var iterator = node.fields(); int count = 0;
            while (iterator.hasNext() && remaining[0] > 0 && count < 32) {
                var entry = iterator.next();
                remaining[0] -= entry.getKey().length() + 16;
                fields.put(entry.getKey(), bounded(entry.getValue(), remaining, depth + 1)); count++;
            }
            return Map.of("fields", fields, "truncated", iterator.hasNext(), "totalFields", node.size());
        }
        if (node.isArray()) {
            var items = new ArrayList<>();
            for (int i = 0; i < node.size() && i < 32 && remaining[0] > 0; i++) {
                remaining[0] -= 16; items.add(bounded(node.get(i), remaining, depth + 1));
            }
            return Map.of("items", items, "truncated", items.size() < node.size(), "totalItems", node.size());
        }
        remaining[0] -= 32;
        return node.isNull() ? null : node;
    }
}
