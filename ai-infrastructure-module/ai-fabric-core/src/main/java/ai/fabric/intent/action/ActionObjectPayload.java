package ai.fabric.intent.action;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Arbitrary structured payload returned from an action.
 *
 * <p>Greenfield: reserved list contract keys are not allowed here. If you need to return a list/search
 * result, use {@link ActionListPayload}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ActionObjectPayload implements ActionPayload {

    @JsonIgnore
    private final Map<String, Object> fields;

    private ActionObjectPayload(Map<String, Object> fields) {
        this.fields = fields;
    }

    public static ActionObjectPayload of(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return new ActionObjectPayload(Map.of());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            String k = key.trim();
            if (k.isEmpty()) {
                return;
            }
            if (ActionResultContracts.isReservedListKey(k)) {
                throw new IllegalArgumentException("Key '" + k + "' is reserved by ActionResultContracts.list");
            }
            out.put(k, value);
        });

        return new ActionObjectPayload(Collections.unmodifiableMap(out));
    }

    @JsonAnyGetter
    public Map<String, Object> getFields() {
        return fields;
    }

    @Override
    public Map<String, Object> toMap() {
        return fields;
    }
}

