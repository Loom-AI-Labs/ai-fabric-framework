package ai.fabric.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Container for all intents extracted from a single user query.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class MultiIntentResponse {

    @Builder.Default
    private List<Intent> intents = new ArrayList<>();

    private String orchestrationStrategy;

    /**
     * Optional metadata returned by the model (confidence summaries, etc.).
     */
    @Builder.Default
    private Map<String, Object> metadata = Collections.emptyMap();

    public void setIntents(List<Intent> intents) {
        this.intents = intents == null ? new ArrayList<>() : new ArrayList<>(intents);
    }

    public void normalize() {
        if (intents == null) {
            intents = new ArrayList<>();
        }
        intents.forEach(Intent::normalize);
        if (metadata == null) {
            metadata = Collections.emptyMap();
        } else {
            metadata = Map.copyOf(metadata);
        }
    }

    public boolean hasIntents() {
        return intents != null && !intents.isEmpty();
    }
}
