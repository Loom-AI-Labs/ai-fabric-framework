package ai.fabric.intent.orchestration.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** A prompt window only. Truncation never removes authoritative journal entries. */
public final class WorkflowObservationWindow {
    private static final int DEFAULT_WINDOW_CHARS = 80000;
    private static final int LATEST_RESULT_CHARS = 48000;
    private static final int LATEST_STRING_CHARS = 32000;
    private static final int LATEST_DEPTH = 16;
    private static final int OLDER_RESULT_CHARS = 4000;
    private static final int MAX_ITEMS = 8;
    private WorkflowObservationWindow() {}

    public static Map<String, Object> project(WorkflowCheckpoint checkpoint, ObjectMapper mapper) {
        return project(checkpoint, mapper, DEFAULT_WINDOW_CHARS);
    }

    public static Map<String, Object> project(WorkflowCheckpoint checkpoint, ObjectMapper mapper, int maxChars) {
        var items = new ArrayList<Map<String, Object>>();
        if (!checkpoint.steps().isEmpty()) {
            items.add(entry(checkpoint.steps().getLast(), mapper, maxChars <= 1024 ? 0 : Math.min(LATEST_RESULT_CHARS, maxChars - 1024), LATEST_STRING_CHARS, LATEST_DEPTH));
            for (int i = checkpoint.steps().size() - 2; i >= Math.max(0, checkpoint.steps().size() - MAX_ITEMS); i--) {
                items.addFirst(entry(checkpoint.steps().get(i), mapper, OLDER_RESULT_CHARS, 1500, 6));
                if (jsonSize(window(checkpoint, items), mapper) > maxChars) { items.removeFirst(); break; }
            }
        }
        var result = window(checkpoint, items);
        if (jsonSize(result, mapper) > maxChars) throw new WorkflowAllowance.Stop("WORKFLOW_CONTEXT_LIMIT");
        return result;
    }

    private static Map<String, Object> window(WorkflowCheckpoint checkpoint, ArrayList<Map<String, Object>> items) {
        int offset = checkpoint.steps().size() - items.size();
        return Map.of("items", items, "stepOffset", offset, "totalStepCount", checkpoint.steps().size(),
            "hasMore", offset > 0, "details", "Read omitted receipt/state details through authorized scoped tools; absence from this window is not completion.");
    }

    private static Map<String, Object> entry(WorkflowCheckpoint.Step step, ObjectMapper mapper, int maxChars, int stringLimit, int depthLimit) {
        JsonNode node = mapper.valueToTree(step.observation());
        int originalSize = jsonSize(node, mapper);
        boolean complete = originalSize <= maxChars && withinShapeBounds(node, 0, stringLimit, depthLimit);
        var value = new LinkedHashMap<String, Object>();
        value.put("operationId", step.operationId()); value.put("receiptRef", step.operationId());
        value.put("action", step.action()); value.put("effect", step.effect());
        value.put("resultTruncated", !complete);
        if (complete) { value.put("result", node); return value; }
        value.put("originalResultChars", originalSize);
        int remaining = maxChars;
        do {
            value.put("result", remaining <= 0 ? Map.of("truncated", true, "receiptRef", step.operationId())
                : bounded(node, new int[] {remaining}, 0, stringLimit, depthLimit));
            int excess = jsonSize(value.get("result"), mapper) - maxChars;
            if (excess <= 0 || remaining <= 0) break;
            remaining -= excess + 256;
        } while (true);
        return value;
    }

    private static boolean withinShapeBounds(JsonNode node, int depth, int strings, int nesting) {
        if (depth > nesting || (node.isTextual() && node.textValue().length() > strings)) return false;
        for (JsonNode child : node) if (!withinShapeBounds(child, depth + 1, strings, nesting)) return false;
        return true;
    }

    private static int jsonSize(Object value, ObjectMapper mapper) {
        try { return mapper.writeValueAsString(value).length(); }
        catch (Exception failure) { throw new IllegalArgumentException("WORKFLOW_CONTEXT_ENCODING_FAILED"); }
    }

    private static Object bounded(JsonNode node, int[] remaining, int depth, int stringLimit, int depthLimit) {
        if (remaining[0] <= 0 || depth > depthLimit) return Map.of("truncated", true, "kind", node.getNodeType().name());
        if (node.isTextual()) {
            String text = node.textValue();
            int size = Math.min(text.length(), Math.min(remaining[0], stringLimit));
            remaining[0] -= size;
            return size == text.length() ? text : Map.of("value", text.substring(0, size), "truncated", true, "originalLength", text.length());
        }
        if (node.isObject()) {
            var fields = new LinkedHashMap<String, Object>();
            var iterator = node.fields(); int count = 0;
            while (iterator.hasNext() && remaining[0] > 0 && count < 32) {
                var entry = iterator.next();
                remaining[0] -= entry.getKey().length() + 16;
                fields.put(entry.getKey(), bounded(entry.getValue(), remaining, depth + 1, stringLimit, depthLimit)); count++;
            }
            return Map.of("fields", fields, "truncated", iterator.hasNext(), "totalFields", node.size());
        }
        if (node.isArray()) {
            var items = new ArrayList<>();
            for (int i = 0; i < node.size() && i < 32 && remaining[0] > 0; i++) {
                remaining[0] -= 16; items.add(bounded(node.get(i), remaining, depth + 1, stringLimit, depthLimit));
            }
            return Map.of("items", items, "truncated", items.size() < node.size(), "totalItems", node.size());
        }
        remaining[0] -= 32;
        return node.isNull() ? null : node;
    }
}
