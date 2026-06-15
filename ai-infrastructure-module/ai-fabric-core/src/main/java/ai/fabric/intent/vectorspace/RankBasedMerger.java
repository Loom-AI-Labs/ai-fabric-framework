package ai.fabric.intent.vectorspace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Merges results from multiple spaces using rank-based interleaving.
 *
 * <p>Rank-based merging is provider-agnostic: similarity score scales vary across vector DBs, but the
 * per-space rank ordering is stable and comparable.</p>
 */
@Slf4j
@Component
public class RankBasedMerger {

    /**
     * Merge ranked lists from multiple spaces by interleaving ranks (rank-1 from each space, then rank-2, ...).
     */
    public <T> List<T> mergeByRank(Map<String, List<T>> resultsBySpace, int topKPerSpace) {
        if (resultsBySpace == null || resultsBySpace.isEmpty() || topKPerSpace <= 0) {
            return List.of();
        }

        List<T> merged = new ArrayList<>();
        for (int rank = 0; rank < topKPerSpace; rank++) {
            for (List<T> docs : resultsBySpace.values()) {
                if (docs != null && rank < docs.size()) {
                    merged.add(docs.get(rank));
                }
            }
        }
        return merged;
    }

    /**
     * De-duplicate a list while preserving order.
     */
    public <T> List<T> dedupePreserveOrder(List<T> items, Function<T, String> keyFn) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (keyFn == null) {
            return List.copyOf(items);
        }

        Set<String> seen = new LinkedHashSet<>();
        List<T> out = new ArrayList<>(items.size());
        for (T item : items) {
            if (item == null) {
                continue;
            }
            String key = keyFn.apply(item);
            if (key == null || key.isBlank()) {
                out.add(item);
                continue;
            }
            if (seen.add(key)) {
                out.add(item);
            }
        }
        if (out.size() != items.size()) {
            log.debug("De-duplicated merged list: {} -> {}", items.size(), out.size());
        }
        return out;
    }
}

