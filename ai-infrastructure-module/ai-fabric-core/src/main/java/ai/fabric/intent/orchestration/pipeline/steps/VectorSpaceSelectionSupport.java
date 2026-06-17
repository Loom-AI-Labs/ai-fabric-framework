package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.intent.KnowledgeBaseOverview;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class VectorSpaceSelectionSupport {

    private final ObjectProvider<KnowledgeBaseOverviewService> knowledgeBaseOverviewServiceProvider;
    private final AIEntityConfigurationLoader entityConfigurationLoader;
    private final VectorSpaceRoutingProperties vectorSpaceRoutingProperties;

    VectorSpaceSelectionSupport(ObjectProvider<KnowledgeBaseOverviewService> knowledgeBaseOverviewServiceProvider,
                                AIEntityConfigurationLoader entityConfigurationLoader,
                                VectorSpaceRoutingProperties vectorSpaceRoutingProperties) {
        this.knowledgeBaseOverviewServiceProvider = knowledgeBaseOverviewServiceProvider;
        this.entityConfigurationLoader = entityConfigurationLoader;
        this.vectorSpaceRoutingProperties = vectorSpaceRoutingProperties;
    }

    List<String> resolveAllVectorSpaces() {
        KnowledgeBaseOverviewService overviewService = knowledgeBaseOverviewServiceProvider != null
            ? knowledgeBaseOverviewServiceProvider.getIfAvailable()
            : null;
        if (overviewService == null) {
            return resolveConfiguredVectorSpaces();
        }

        KnowledgeBaseOverview overview = overviewService.getOverview();
        if (overview == null) {
            return resolveConfiguredVectorSpaces();
        }

        List<String> entityTypes = overview.getEntityTypes();
        Map<String, Long> byType = overview.getDocumentsByType();

        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (byType != null && !byType.isEmpty()) {
            byType.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.nullsLast(Long::compareTo)).reversed())
                .map(Map.Entry::getKey)
                .forEach(ordered::add);
        }
        if (entityTypes != null && !entityTypes.isEmpty()) {
            entityTypes.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(ordered::add);
        }
        if (ordered.isEmpty() && byType != null && !byType.isEmpty()) {
            byType.keySet().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(ordered::add);
        }

        entityTypes = ordered.isEmpty() ? null : new ArrayList<>(ordered);
        if (entityTypes == null || entityTypes.isEmpty()) {
            return resolveConfiguredVectorSpaces();
        }

        return entityTypes.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    }

    List<String> resolveConfiguredVectorSpaces() {
        if (entityConfigurationLoader == null) {
            return List.of();
        }
        Set<String> supported = entityConfigurationLoader.getSupportedEntityTypes();
        if (supported == null || supported.isEmpty()) {
            return List.of();
        }
        return supported.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .map(s -> s.toLowerCase(Locale.ROOT))
            .distinct()
            .sorted()
            .toList();
    }

    List<String> resolveDeterministicFallbackVectorSpaces(OrchestrationPolicy.RagBudgets ragBudgets) {
        List<String> spaces = resolveAllVectorSpaces();
        if (spaces.isEmpty()) {
            return spaces;
        }

        Integer maxOverride = ragBudgets != null ? ragBudgets.maxSpaces() : null;
        if (maxOverride != null && maxOverride > 0) {
            return spaces.size() > maxOverride ? spaces.subList(0, maxOverride) : spaces;
        }

        int maxDefault = vectorSpaceRoutingProperties != null ? vectorSpaceRoutingProperties.getFanOutMaxSpaces() : 3;
        if (maxDefault <= 0) {
            return spaces;
        }
        return spaces.size() > maxDefault ? spaces.subList(0, maxDefault) : spaces;
    }

    List<String> capVectorSpacesToBudget(List<String> vectorSpaces, OrchestrationPolicy.RagBudgets ragBudgets) {
        if (vectorSpaces == null || vectorSpaces.isEmpty() || ragBudgets == null) {
            return vectorSpaces;
        }
        Integer maxSpaces = ragBudgets.maxSpaces();
        if (maxSpaces == null || maxSpaces <= 0) {
            return vectorSpaces;
        }
        return vectorSpaces.size() > maxSpaces ? vectorSpaces.subList(0, maxSpaces) : vectorSpaces;
    }
}
