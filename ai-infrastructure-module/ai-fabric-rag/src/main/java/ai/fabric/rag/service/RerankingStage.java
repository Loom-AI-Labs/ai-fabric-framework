package ai.fabric.rag.service;

import ai.fabric.rag.config.RAGProperties;
import ai.fabric.spi.RerankingProvider;
import ai.fabric.spi.RerankingUnavailableException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs after source authorization/fusion, before final evidence selection. */
final class RerankingStage {
    record Ranked(List<Map<String, Object>> candidates, Map<String, Object> diagnostics) { }
    private final RerankingProvider provider;
    private final RAGProperties.RerankingProperties settings;

    RerankingStage(RerankingProvider provider, RAGProperties.RerankingProperties settings) {
        if (provider == null || !provider.providerId().equals(settings.getProvider())
            || !provider.modelId().equals(settings.getModel())) {
            throw new IllegalArgumentException("An explicitly matched reranking provider, model and accounting bridge are required");
        }
        if (settings.getFailurePolicy() == null || settings.getMaxCandidates() < 1 || settings.getMaxCandidates() > 100
            || settings.getMaxCharactersPerCandidate() < 1 || settings.getMaxCharactersPerCandidate() > 16000
            || settings.getMaxTotalCharacters() < 1 || settings.getMaxTotalCharacters() > 200000
            || settings.getMaxTokensPerDocument() < 1 || settings.getMaxTokensPerDocument() > 4096
            || settings.getTimeoutMs() < 1 || settings.getTimeoutMs() > 30000) {
            throw new IllegalArgumentException("Reranking resource allowances are out of bounds");
        }
        this.provider = provider;
        this.settings = settings;
    }

    Ranked rank(String query, List<Map<String, Object>> candidates, long deadline) {
        var input = new ArrayList<RerankingProvider.Candidate>();
        Map<String, Map<String, Object>> originals = new LinkedHashMap<>();
        int characters = 0;
        int truncated = 0;
        for (int i = 0; i < Math.min(candidates.size(), settings.getMaxCandidates()); i++) {
            var candidate = candidates.get(i);
            if (!(candidate.get("content") instanceof String text) || text.isBlank()) continue;
            int length = Math.min(text.length(), Math.min(settings.getMaxCharactersPerCandidate(), settings.getMaxTotalCharacters() - characters));
            if (length <= 0) break;
            if (length < text.length()) truncated++;
            String reference = "candidate-" + i;
            input.add(new RerankingProvider.Candidate(reference, text.substring(0, length)));
            originals.put(reference, candidate);
            characters += length;
        }
        if (input.isEmpty()) return new Ranked(candidates, Map.of("used", false, "status", "NO_CANDIDATE_TEXT"));
        long remainingMs = Math.min(settings.getTimeoutMs(), (deadline - System.nanoTime()) / 1_000_000);
        RerankingProvider.Result result;
        try {
            if (remainingMs < 1) throw new RerankingUnavailableException(RerankingUnavailableException.Reason.TIMEOUT);
            result = provider.rerank(new RerankingProvider.Request(query, input, Duration.ofMillis(remainingMs), settings.getMaxTokensPerDocument()));
        } catch (RerankingUnavailableException unavailable) {
            if (settings.getFailurePolicy() != RAGProperties.RerankingProperties.FailurePolicy.VISIBLE_PRE_RERANK_FALLBACK) throw unavailable;
            return new Ranked(List.copyOf(candidates), Map.of("used", false, "status", "PRE_RERANK_FALLBACK",
                "reason", unavailable.reason().name(), "provider", provider.providerId(), "model", provider.modelId()));
        }
        java.util.Objects.requireNonNull(result, "Reranking receipt is required");
        var ranked = new ArrayList<Map<String, Object>>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var score : result.candidates()) {
            if (!originals.containsKey(score.reference()) || !seen.add(score.reference())) {
                throw new SecurityException("Reranking returned an unknown or duplicate candidate reference");
            }
            var original = originals.get(score.reference());
            var row = new LinkedHashMap<>(original);
            var metadata = new LinkedHashMap<>(RAGMetadataSupport.normalizeMetadata(original.get("metadata")));
            var scores = new LinkedHashMap<>(RAGMetadataSupport.normalizeMetadata(metadata.get("retrievalScores")));
            scores.put("rerankerScore", score.relevance());
            scores.put("rerankerRank", ranked.size() + 1);
            scores.put("rerankerProvider", provider.providerId());
            scores.put("rerankerModel", provider.modelId());
            metadata.put("retrievalScores", Map.copyOf(scores));
            row.put("metadata", metadata);
            ranked.add(row);
        }
        // Unscored/over-budget candidates retain their original order and content.
        for (int i = 0; i < candidates.size(); i++) {
            if (!seen.contains("candidate-" + i)) ranked.add(candidates.get(i));
        }
        return new Ranked(List.copyOf(ranked), Map.of("used", true, "provider", provider.providerId(),
            "model", provider.modelId(), "candidateCount", input.size(), "scoredCount", seen.size(),
            "inputCharacters", characters, "truncatedExcerpts", truncated, "billedUnits", result.usage().billedUnits()));
    }
}
