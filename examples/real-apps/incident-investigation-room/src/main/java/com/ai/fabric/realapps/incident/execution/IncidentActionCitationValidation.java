package com.ai.fabric.realapps.incident.execution;

import ai.fabric.execution.specialist.manifest.SpecialistFinalOutputValidationContext;
import ai.fabric.execution.specialist.manifest.SpecialistOutputNormalizationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IncidentActionCitationValidation {

    private final ObjectMapper objectMapper;

    IncidentActionCitationValidation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void validateService(SpecialistFinalOutputValidationContext context) {
        validate(
            context,
            Set.of("read_service_metrics", "read_incident_alerts"),
            false
        );
    }

    void validateChange(SpecialistFinalOutputValidationContext context) {
        validate(
            context,
            Set.of("read_recent_deployments", "read_change_approvals"),
            true
        );
    }

    JsonNode normalizeService(SpecialistOutputNormalizationContext context) {
        return normalize(
            context,
            Set.of("read_service_metrics", "read_incident_alerts")
        );
    }

    JsonNode normalizeChange(SpecialistOutputNormalizationContext context) {
        return normalize(
            context,
            Set.of("read_recent_deployments", "read_change_approvals")
        );
    }

    private void validate(
        SpecialistFinalOutputValidationContext context,
        Set<String> allowedActions,
        boolean requireRunbook
    ) {
        Map<String, ActionObservation> observations = observations(
            context.sourceResult(),
            allowedActions
        );
        if (observations.isEmpty()) {
            throw new IllegalArgumentException(
                "A validated incident finding requires successful read-action evidence"
            );
        }

        JsonNode output = context.output();
        Set<String> outputActions = new LinkedHashSet<>();
        JsonNode sources = output.path("dataSources");
        if (!sources.isArray() || sources.isEmpty()) {
            throw new IllegalArgumentException(
                "Incident finding must identify the read actions used"
            );
        }
        for (JsonNode source : sources) {
            String action = requiredText(source, "action");
            ActionObservation observation = observations.get(action);
            if (observation == null
                || !source.path("groundingUsable").asBoolean(false)) {
                throw new IllegalArgumentException(
                    "Incident data-source trace conflicts with canonical action evidence"
                );
            }
            if (!outputActions.add(action)) {
                throw new IllegalArgumentException(
                    "Incident data-source trace contains a duplicate action"
                );
            }
        }
        if (!outputActions.equals(observations.keySet())) {
            throw new IllegalArgumentException(
                "Incident data-source trace omitted or invented an executed action"
            );
        }

        Set<String> availableEventIds = new LinkedHashSet<>();
        observations.values().forEach(observation -> {
            availableEventIds.addAll(observation.candidateIds());
        });
        Set<String> citedEventIds = requiredTextSet(
            output.path("evidenceIds"),
            "evidenceIds"
        );
        if (!availableEventIds.containsAll(citedEventIds)) {
            throw new IllegalArgumentException(
                "Incident finding cited an event outside executed action evidence"
            );
        }
        if (requireRunbook) {
            Set<String> availableRunbooks = context.evidence().stream()
                .map(reference -> reference.evidenceId())
                .collect(java.util.stream.Collectors.toCollection(
                    LinkedHashSet::new
                ));
            Set<String> citedRunbooks = requiredTextSet(
                output.path("runbookEvidenceIds"),
                "runbookEvidenceIds"
            );
            if (!availableRunbooks.containsAll(citedRunbooks)) {
                throw new IllegalArgumentException(
                    "Change finding cited a runbook outside retrieved evidence"
                );
            }
        }
    }

    private JsonNode normalize(
        SpecialistOutputNormalizationContext context,
        Set<String> allowedActions
    ) {
        Map<String, ActionObservation> observations = observations(
            context.sourceResult(),
            allowedActions
        );
        if (observations.isEmpty()) {
            throw new IllegalArgumentException(
                "Canonical incident action evidence is required"
            );
        }
        Set<String> sourceRevisions = observations.values().stream()
            .map(ActionObservation::sourceRevision)
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new
            ));
        if (sourceRevisions.size() != 1) {
            throw new IllegalArgumentException(
                "Executed incident actions disagree on source revision"
            );
        }

        ObjectNode normalized = requireObject(context.output()).deepCopy();
        ArrayNode dataSources = objectMapper.createArrayNode();
        int candidateCount = 0;
        for (ActionObservation observation : observations.values()) {
            ObjectNode source = dataSources.addObject();
            source.put("action", observation.action());
            source.put("candidateCount", observation.candidateIds().size());
            source.put("groundingUsable", true);
            candidateCount += observation.candidateIds().size();
        }
        normalized.set("dataSources", dataSources);
        normalized.put("candidateEventCount", candidateCount);
        normalized.put("sourceRevision", sourceRevisions.iterator().next());
        return normalized;
    }

    private ObjectNode requireObject(JsonNode output) {
        if (output instanceof ObjectNode object) {
            return object;
        }
        throw new IllegalArgumentException(
            "Incident finding must be a JSON object"
        );
    }

    private Map<String, ActionObservation> observations(
        OrchestrationResult result,
        Set<String> allowedActions
    ) {
        List<Map<?, ?>> actionDiagnostics = new ArrayList<>();
        collectActions(
            result,
            actionDiagnostics,
            Collections.newSetFromMap(new IdentityHashMap<>())
        );
        Map<String, ActionObservation> observations = new LinkedHashMap<>();
        for (Map<?, ?> action : actionDiagnostics) {
            Object rawAction = action.get("action");
            if (!(rawAction instanceof String actionName)
                || !allowedActions.contains(actionName)
                || !Boolean.TRUE.equals(action.get("groundingUsable"))) {
                continue;
            }
            Object rawSummary = action.get("evidenceSummary");
            if (!(rawSummary instanceof String summary) || summary.isBlank()) {
                continue;
            }
            observations.put(actionName, parseObservation(actionName, summary));
        }
        return Collections.unmodifiableMap(observations);
    }

    private ActionObservation parseObservation(
        String expectedAction,
        String summary
    ) {
        try {
            JsonNode facts = objectMapper.readTree(summary);
            String action = requiredText(facts, "action");
            if (!expectedAction.equals(action)) {
                throw new IllegalArgumentException(
                    "Action evidence name does not match its canonical source"
                );
            }
            return new ActionObservation(
                action,
                requiredText(facts, "sourceRevision"),
                requiredTextSet(
                    facts.path("candidateEventIds"),
                    "candidateEventIds"
                )
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Canonical incident action evidence is invalid",
                exception
            );
        }
    }

    private void collectActions(
        OrchestrationResult result,
        List<Map<?, ?>> actions,
        Set<OrchestrationResult> visited
    ) {
        if (result == null || !visited.add(result)) {
            return;
        }
        Map<String, Object> data = result.getData();
        if (data != null
            && data.get("readActionResolution") instanceof Map<?, ?> resolution
            && resolution.get("executedActions") instanceof List<?> executed) {
            executed.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .forEach(actions::add);
        }
        if (result.getChildren() != null) {
            result.getChildren().forEach(child ->
                collectActions(child, actions, visited)
            );
        }
    }

    private Set<String> requiredTextSet(JsonNode value, String field) {
        if (!value.isArray() || value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(
                    field + " must contain non-blank strings"
                );
            }
            if (!values.add(item.asText().trim())) {
                throw new IllegalArgumentException(
                    field + " must not contain duplicates"
                );
            }
        }
        return values;
    }

    private String requiredText(JsonNode value, String field) {
        JsonNode selected = value.path(field);
        if (!selected.isTextual() || selected.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return selected.asText().trim();
    }

    private record ActionObservation(
        String action,
        String sourceRevision,
        Set<String> candidateIds
    ) {}
}
