package ai.fabric.intent;

import ai.fabric.intent.action.ActionInfo;
import ai.fabric.spi.BehaviorContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bundles the contextual information passed to the intent extraction prompt.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemContext {

    @Builder.Default
    private List<ActionInfo> availableActions = List.of();

    private KnowledgeBaseOverview knowledgeBaseOverview;

    // May be null for anonymous users
    private String userId;

    // Session ID for anonymous tracking
    private String sessionId;

    // Authentication status
    private Boolean authenticated;

    // User locale
    private Locale locale;

    // Additional metadata
    private Map<String, Object> metadata;

    private LocalDateTime timestamp;

    // Optional behavior insights (only when provider is present)
    private BehaviorContext behaviorContext;

    // Available entity types for relationship queries (only when relationship-query module is present)
    @Builder.Default
    private Set<String> availableEntityTypes = Set.of();

    public boolean isAuthenticated() {
        return Boolean.TRUE.equals(authenticated);
    }

    public String getIdentifier() {
        return isAuthenticated() ? userId : sessionId;
    }

    public boolean hasBehaviorContext() {
        return behaviorContext != null;
    }

    public void setAvailableActions(List<ActionInfo> availableActions) {
        this.availableActions = availableActions == null ? List.of() : List.copyOf(availableActions);
    }

    public void setKnowledgeBaseOverview(KnowledgeBaseOverview knowledgeBaseOverview) {
        this.knowledgeBaseOverview = knowledgeBaseOverview;
    }
}
