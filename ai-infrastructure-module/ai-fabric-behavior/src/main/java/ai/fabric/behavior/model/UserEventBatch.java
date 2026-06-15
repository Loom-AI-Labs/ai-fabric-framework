package ai.fabric.behavior.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class UserEventBatch {
    private String userId;
    private List<ExternalEvent> events;
    private int totalEventCount;
    private Map<String, Object> userContext;
}
