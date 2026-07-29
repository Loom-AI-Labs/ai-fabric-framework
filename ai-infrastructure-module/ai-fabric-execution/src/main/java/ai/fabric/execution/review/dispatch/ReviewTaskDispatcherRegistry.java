package ai.fabric.execution.review.dispatch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReviewTaskDispatcherRegistry {

    private final Map<String, ReviewTaskDispatcher> dispatchers;

    public ReviewTaskDispatcherRegistry(List<ReviewTaskDispatcher> values) {
        Map<String, ReviewTaskDispatcher> loaded = new LinkedHashMap<>();
        for (ReviewTaskDispatcher value :
            values == null ? List.<ReviewTaskDispatcher>of() : values) {
            Objects.requireNonNull(value, "dispatcher must not be null");
            String id = requireId(value.id());
            if (loaded.putIfAbsent(id, value) != null) {
                throw new IllegalStateException(
                    "Duplicate review dispatcher: " + id
                );
            }
        }
        this.dispatchers = Map.copyOf(loaded);
    }

    public ReviewTaskDispatcher require(String id) {
        String normalized = requireId(id);
        ReviewTaskDispatcher dispatcher = dispatchers.get(normalized);
        if (dispatcher == null) {
            throw new IllegalArgumentException(
                "Review dispatcher is not registered: " + normalized
            );
        }
        return dispatcher;
    }

    public List<ReviewTaskDispatcher> list() {
        return List.copyOf(dispatchers.values());
    }

    private static String requireId(String value) {
        String normalized = Objects.requireNonNull(
            value,
            "dispatcher ID is required"
        ).trim();
        if (!normalized.matches(
                "[a-z][a-z0-9-]{0,79}@[A-Za-z0-9][A-Za-z0-9._-]{0,39}"
            )) {
            throw new IllegalArgumentException(
                "Dispatcher ID must use lowercase-name@version"
            );
        }
        return normalized;
    }
}
