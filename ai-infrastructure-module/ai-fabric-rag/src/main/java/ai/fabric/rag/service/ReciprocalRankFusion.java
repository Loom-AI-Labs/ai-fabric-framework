package ai.fabric.rag.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Equal-weight fusion of already authorized, ranked lists. No score normalization. */
final class ReciprocalRankFusion {
    record RankedList(String sourceId, String entityType, String leg, List<Map<String, Object>> results) {
        RankedList {
            if (!List.of("vector", "lexical").contains(leg)) {
                throw new IllegalArgumentException("Unknown retrieval leg");
            }
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    private record Identity(String source, String type, String document, String version, String chunk, String id)
        implements Comparable<Identity> {
        @Override
        public int compareTo(Identity other) {
            List<String> left = List.of(source, type, document, version, chunk, id);
            List<String> right = List.of(other.source, other.type, other.document, other.version, other.chunk, other.id);
            for (int i = 0; i < left.size(); i++) {
                int comparison = left.get(i).compareTo(right.get(i));
                if (comparison != 0) return comparison;
            }
            return 0;
        }
    }

    private static final class Candidate {
        final Map<String, Object> record;
        final Map<String, Object> scores = new LinkedHashMap<>();
        double fusionScore;
        Candidate(Map<String, Object> record) { this.record = record; }
    }

    static List<Map<String, Object>> fuse(List<RankedList> lists, int rankConstant, int limit) {
        if (rankConstant < 1 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("Invalid bounded fusion options");
        }
        Map<Identity, Candidate> merged = new LinkedHashMap<>();
        for (RankedList list : lists) {
            java.util.Set<Identity> seen = new java.util.HashSet<>();
            int rank = 0;
            for (Map<String, Object> record : list.results()) {
                Identity identity = identity(list, record);
                Candidate candidate = merged.computeIfAbsent(identity, ignored -> new Candidate(record));
                requireConsistentContent(candidate.record, record);
                if (!seen.add(identity)) continue;
                rank++;
                candidate.fusionScore += 1.0 / (rankConstant + rank);
                candidate.scores.put(list.leg() + "Rank", rank);
                Object score = record.get("score");
                if (score instanceof Number number && Double.isFinite(number.doubleValue())) {
                    candidate.scores.put(list.leg() + "Score", number.doubleValue());
                }
            }
        }
        List<Map.Entry<Identity, Candidate>> ranked = new ArrayList<>(merged.entrySet());
        ranked.sort(Comparator.<Map.Entry<Identity, Candidate>>comparingDouble(e -> e.getValue().fusionScore)
            .reversed().thenComparing(Map.Entry::getKey));
        List<Map<String, Object>> output = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, ranked.size()); i++) {
            Candidate candidate = ranked.get(i).getValue();
            Map<String, Object> record = new LinkedHashMap<>(candidate.record);
            Map<String, Object> metadata = new LinkedHashMap<>(RAGMetadataSupport.normalizeMetadata(record.get("metadata")));
            Map<String, Object> scores = new LinkedHashMap<>(candidate.scores);
            scores.put("scoreType", "RRF");
            scores.put("fusionScore", candidate.fusionScore);
            scores.put("fusionRank", i + 1);
            metadata.put("retrievalScores", Map.copyOf(scores));
            record.put("metadata", metadata);
            record.put("score", candidate.fusionScore);
            // BM25 and RRF scores are not cosine similarity or confidence percentages.
            record.put("similarity", scores.getOrDefault("vectorScore", 0.0));
            output.add(record);
        }
        return List.copyOf(output);
    }

    static List<Map<String, Object>> rankSingleLeg(List<RankedList> lists, int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("Invalid bounded retrieval limit");
        }
        Map<Identity, Map<String, Object>> merged = new java.util.TreeMap<>();
        for (RankedList list : lists) {
            for (Map<String, Object> record : list.results()) {
                Map<String, Object> decorated = new LinkedHashMap<>(record);
                Map<String, Object> metadata = new LinkedHashMap<>(RAGMetadataSupport.normalizeMetadata(record.get("metadata")));
                Map<String, Object> scores = new LinkedHashMap<>();
                scores.put("scoreType", "lexical".equals(list.leg()) ? "BM25" : "VECTOR");
                if (record.get("score") instanceof Number score && Double.isFinite(score.doubleValue())) {
                    scores.put(list.leg() + "Score", score.doubleValue());
                }
                metadata.put("retrievalScores", Map.copyOf(scores));
                decorated.put("metadata", metadata);
                Map<String, Object> existing = merged.putIfAbsent(identity(list, record), decorated);
                if (existing != null) requireConsistentContent(existing, record);
            }
        }
        return merged.entrySet().stream()
            .sorted(Comparator.<Map.Entry<Identity, Map<String, Object>>>comparingDouble(
                entry -> RAGMetadataSupport.extractScore(entry.getValue())).reversed().thenComparing(Map.Entry::getKey))
            .limit(limit).map(Map.Entry::getValue).toList();
    }

    private static void requireConsistentContent(Map<String, Object> existing, Map<String, Object> record) {
        if (!java.util.Objects.equals(existing.get("content"), record.get("content"))) {
            throw new IllegalStateException("Retrieval legs returned conflicting content for one versioned identity");
        }
    }

    private static Identity identity(RankedList list, Map<String, Object> record) {
        Map<String, Object> metadata = RAGMetadataSupport.normalizeMetadata(record.get("metadata"));
        String id = text(record.get("id"));
        if (id.isBlank()) throw new IllegalArgumentException("Versioned retrieval candidate requires an ID");
        return new Identity(text(list.sourceId()), text(list.entityType()), text(metadata.get("_aiDocumentSourceId")),
            text(metadata.getOrDefault("_aiDocumentSourceVersion",
                metadata.getOrDefault("_aifabricSourceVersion", metadata.getOrDefault("entityVersion", "")))),
            text(metadata.get("_aiDocumentChunkId")), id);
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }
}
