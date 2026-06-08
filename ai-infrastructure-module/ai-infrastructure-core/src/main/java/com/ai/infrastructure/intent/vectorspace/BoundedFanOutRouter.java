package com.ai.infrastructure.intent.vectorspace;

import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.intent.KnowledgeBaseOverview;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Routes missing vectorSpace values using knowledge-base facts and a bounded fallback ladder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BoundedFanOutRouter implements VectorSpaceRouter {

    private final ObjectProvider<KnowledgeBaseOverviewService> overviewServiceProvider;
    private final VectorSpaceRoutingProperties properties;

    @Override
    public RoutingResult route(Intent intent, String originalQuery) {
        KnowledgeBaseOverview overview = getOverview();
        if (overview == null || overview.getEntityTypes() == null || overview.getEntityTypes().isEmpty()) {
            return RoutingResult.builder()
                .success(false)
                .strategy(RoutingStrategy.CLARIFICATION)
                .confidence(0.0d)
                .rationale("No entity types available for routing (knowledge base not configured or empty)")
                .build();
        }

        List<String> entityTypes = overview.getEntityTypes().stream()
            .filter(type -> type != null && !type.isBlank())
            .toList();
        if (entityTypes.isEmpty()) {
            return RoutingResult.builder()
                .success(false)
                .strategy(RoutingStrategy.CLARIFICATION)
                .confidence(0.0d)
                .rationale("Knowledge base reported empty entity types list")
                .build();
        }

        if (entityTypes.size() == 1) {
            String only = entityTypes.getFirst();
            return RoutingResult.builder()
                .success(true)
                .vectorSpace(only)
                .strategy(RoutingStrategy.AUTO_ASSIGN)
                .confidence(1.0d)
                .rationale("Only one entity type available")
                .build();
        }

        String mentionMatch = tryQueryMentionMatch(entityTypes, originalQuery);
        if (mentionMatch != null) {
            return RoutingResult.builder()
                .success(true)
                .vectorSpace(mentionMatch)
                .strategy(RoutingStrategy.QUERY_MENTION)
                .confidence(0.8d)
                .rationale("Matched entity type '" + mentionMatch + "' mentioned in query")
                .build();
        }

        VectorSpaceRoutingProperties.Strategy strategy = properties != null
            ? properties.getStrategy()
            : VectorSpaceRoutingProperties.Strategy.BOUNDED_FAN_OUT;

        if (strategy == VectorSpaceRoutingProperties.Strategy.CLARIFICATION) {
            return RoutingResult.builder()
                .success(false)
                .candidateSpaces(selectTopNCandidates(entityTypes, overview.getDocumentsByType(),
                    properties != null ? properties.getFanOutMaxSpaces() : 3))
                .strategy(RoutingStrategy.CLARIFICATION)
                .confidence(0.0d)
                .rationale("Multiple entity types present; configured to require clarification")
                .build();
        }

        if (strategy == VectorSpaceRoutingProperties.Strategy.HEURISTIC) {
            String chosen = heuristicPick(entityTypes, overview.getDocumentsByType());
            return RoutingResult.builder()
                .success(true)
                .vectorSpace(chosen)
                .strategy(RoutingStrategy.HEURISTIC)
                .confidence(0.5d)
                .rationale("Heuristic selection due to missing vectorSpace")
                .build();
        }

        int maxSpaces = properties != null ? properties.getFanOutMaxSpaces() : 3;
        List<String> topN = selectTopNCandidates(entityTypes, overview.getDocumentsByType(), maxSpaces);
        return RoutingResult.builder()
            .success(true)
            .candidateSpaces(topN)
            .strategy(RoutingStrategy.FAN_OUT)
            .confidence(0.5d)
            .rationale("Multiple entity types; using bounded fan-out to " + topN.size() + " spaces")
            .build();
    }

    private KnowledgeBaseOverview getOverview() {
        KnowledgeBaseOverviewService service = overviewServiceProvider != null ? overviewServiceProvider.getIfAvailable() : null;
        if (service == null) {
            return null;
        }
        try {
            return service.getOverview();
        } catch (Exception ex) {
            log.warn("Failed to read knowledge base overview for vectorSpace routing: {}", ex.getMessage());
            return null;
        }
    }

    private String tryQueryMentionMatch(List<String> entityTypes, String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return null;
        }
        String queryLower = originalQuery.toLowerCase(Locale.ROOT);
        for (String type : entityTypes) {
            if (type == null || type.isBlank()) {
                continue;
            }
            if (queryMentionsType(queryLower, type)) {
                return type;
            }
        }
        return null;
    }

    private boolean queryMentionsType(String queryLower, String type) {
        String normalized = type.toLowerCase(Locale.ROOT);
        if (queryLower.contains(normalized)) {
            return true;
        }

        String[] tokens = normalized.split("[^a-z0-9]+");
        for (String token : tokens) {
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (trimmed.length() < 3) {
                continue;
            }
            if ("test".equals(trimmed) || "kb".equals(trimmed) || "doc".equals(trimmed) || "docs".equals(trimmed)) {
                continue;
            }
            if (queryLower.contains(trimmed) || queryLower.contains(trimmed + "s")) {
                return true;
            }
        }
        return false;
    }

    private String heuristicPick(List<String> entityTypes, Map<String, Long> counts) {
        if (counts != null && !counts.isEmpty()) {
            return counts.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(entityTypes.getFirst());
        }
        return entityTypes.getFirst();
    }

    private List<String> selectTopNCandidates(List<String> entityTypes, Map<String, Long> counts, int maxSpaces) {
        int effectiveMax = Math.max(1, maxSpaces);
        Set<String> ordered = new LinkedHashSet<>();

        if (counts != null && !counts.isEmpty()) {
            counts.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.nullsLast(Long::compareTo)).reversed())
                .map(Map.Entry::getKey)
                .forEach(ordered::add);
        }

        for (String type : entityTypes) {
            if (type != null && !type.isBlank()) {
                ordered.add(type);
            }
        }

        List<String> out = new ArrayList<>(Math.min(effectiveMax, ordered.size()));
        for (String type : ordered) {
            if (out.size() >= effectiveMax) {
                break;
            }
            out.add(type);
        }
        return out;
    }
}

