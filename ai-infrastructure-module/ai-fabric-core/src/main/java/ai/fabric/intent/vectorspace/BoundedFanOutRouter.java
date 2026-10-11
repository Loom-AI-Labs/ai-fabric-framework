package ai.fabric.intent.vectorspace;

import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.dto.Intent;
import ai.fabric.intent.KnowledgeBaseOverview;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
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

        VectorSpaceRoutingProperties.Strategy strategy = properties != null
            ? properties.getStrategy()
            : VectorSpaceRoutingProperties.Strategy.CLARIFICATION;

        if (strategy != VectorSpaceRoutingProperties.Strategy.BOUNDED_FAN_OUT) {
            return RoutingResult.builder()
                .success(false)
                .candidateSpaces(selectTopNCandidates(entityTypes, overview.getDocumentsByType(),
                    properties != null ? properties.getFanOutMaxSpaces() : 3))
                .strategy(RoutingStrategy.CLARIFICATION)
                .confidence(0.0d)
                .rationale("Vector-space selection requires a model decision or explicitly authorized bounded fan-out")
                .build();
        }

        if (entityTypes.size() == 1) {
            String only = entityTypes.getFirst();
            return RoutingResult.builder()
                .success(true)
                .vectorSpace(only)
                .strategy(RoutingStrategy.AUTO_ASSIGN)
                .confidence(1.0d)
                .rationale("Explicit bounded fan-out contains one allowed entity type")
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
