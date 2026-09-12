package com.ai.fabric.realapps.incident.action;

import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import com.ai.fabric.realapps.incident.domain.AuthorizedIncidentScope;
import com.ai.fabric.realapps.incident.domain.IncidentEvent;
import com.ai.fabric.realapps.incident.domain.IncidentEventQuery;
import com.ai.fabric.realapps.incident.domain.IncidentEventType;
import com.ai.fabric.realapps.incident.service.IncidentActionScopeResolver;
import com.ai.fabric.realapps.incident.service.IncidentEventRepository;
import com.ai.fabric.realapps.incident.service.IncidentEventSourceUnavailableException;
import com.ai.fabric.realapps.incident.service.IncidentInvocationMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class IncidentReadActionSupport {

    static final int DEFAULT_WINDOW_MINUTES = 60;
    static final int DEFAULT_LIMIT = 6;

    private final IncidentActionScopeResolver scopes;
    private final IncidentEventRepository events;
    private final IncidentInvocationMetrics metrics;

    public IncidentReadActionSupport(
        IncidentActionScopeResolver scopes,
        IncidentEventRepository events,
        IncidentInvocationMetrics metrics
    ) {
        this.scopes = scopes;
        this.events = events;
        this.metrics = metrics;
    }

    public boolean allowed(ActionContext context, String actionName) {
        return scopes.allowed(context, actionName);
    }

    public ActionResult execute(
        ActionContext context,
        String actionName,
        Set<IncidentEventType> eventTypes,
        Integer windowMinutes,
        Integer limit
    ) {
        try {
            AuthorizedIncidentScope scope = scopes.resolve(context, actionName);
            metrics.recordActionCall(actionName);
            IncidentEventQuery query = new IncidentEventQuery(
                eventTypes,
                boundedWindow(windowMinutes),
                boundedLimit(limit),
                actionName
            );
            List<IncidentEvent> candidates = events.findAuthorized(query, scope);
            Map<String, Object> data = canonicalPayload(
                actionName,
                scope,
                query,
                candidates
            );
            return ActionResult.builder()
                .success(true)
                .message("Authorized incident evidence loaded")
                .data(ActionResultContracts.object(data))
                .build();
        } catch (IncidentEventSourceUnavailableException exception) {
            return ActionResult.builder()
                .success(false)
                .message(exception.getMessage())
                .errorCode("INCIDENT_SOURCE_UNAVAILABLE")
                .build();
        } catch (IllegalArgumentException exception) {
            return ActionResult.builder()
                .success(false)
                .message("Incident evidence request was rejected")
                .errorCode("INCIDENT_SCOPE_OR_FILTER_DENIED")
                .build();
        }
    }

    public Map<String, Object> facts(ActionResult result) {
        if (result == null || !result.isSuccess() || result.getData() == null) {
            return Map.of();
        }
        Map<String, Object> data = result.getData().toMap();
        Map<String, Object> facts = new LinkedHashMap<>();
        copy(data, facts, "factSource");
        copy(data, facts, "action");
        copy(data, facts, "sourceRevision");
        copy(data, facts, "queryWindowMinutes");
        copy(data, facts, "candidateEventCount");
        copy(data, facts, "candidateEventIds");
        copy(data, facts, "events");
        return Map.copyOf(facts);
    }

    private Map<String, Object> canonicalPayload(
        String actionName,
        AuthorizedIncidentScope scope,
        IncidentEventQuery query,
        List<IncidentEvent> candidates
    ) {
        List<Map<String, Object>> eventFacts = candidates.stream()
            .map(this::safeEvent)
            .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("factSource", "authorized_incident_events");
        data.put("action", actionName);
        data.put("sourceRevision", scope.sourceRevision());
        data.put("queryWindowMinutes", query.windowMinutes());
        data.put("candidateEventCount", eventFacts.size());
        data.put("candidateEventIds", candidates.stream()
            .map(IncidentEvent::id)
            .toList());
        data.put("events", eventFacts);
        return Map.copyOf(data);
    }

    private Map<String, Object> safeEvent(IncidentEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", event.id());
        out.put("type", event.type().name());
        out.put("source", event.source());
        out.put("summary", event.summary());
        out.put("severity", event.severity());
        out.put("observedAt", event.observedAt().toString());
        return Map.copyOf(out);
    }

    private int boundedWindow(Integer requested) {
        return requested == null ? DEFAULT_WINDOW_MINUTES : requested;
    }

    private int boundedLimit(Integer requested) {
        return requested == null ? DEFAULT_LIMIT : requested;
    }

    private void copy(
        Map<String, Object> source,
        Map<String, Object> target,
        String key
    ) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }
}
