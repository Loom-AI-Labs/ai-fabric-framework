package ai.fabric.execution.review.continuation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReviewCorrectionHandlerRegistry {

    private final Map<String, ReviewCorrectionHandler> handlers;

    public ReviewCorrectionHandlerRegistry(
        List<ReviewCorrectionHandler> values
    ) {
        Map<String, ReviewCorrectionHandler> loaded = new LinkedHashMap<>();
        for (ReviewCorrectionHandler value :
            values == null ? List.<ReviewCorrectionHandler>of() : values) {
            Objects.requireNonNull(value, "handler must not be null");
            String id = exactId(value.id());
            if (loaded.putIfAbsent(id, value) != null) {
                throw new IllegalStateException(
                    "Duplicate review correction handler: " + id
                );
            }
        }
        this.handlers = Map.copyOf(loaded);
    }

    public ReviewCorrectionHandler require(String id) {
        ReviewCorrectionHandler handler = handlers.get(exactId(id));
        if (handler == null) {
            throw new IllegalArgumentException(
                "Review correction handler is not registered: " + id
            );
        }
        return handler;
    }

    private static String exactId(String value) {
        String normalized = Objects.requireNonNull(
            value,
            "handler ID is required"
        ).trim();
        if (!normalized.matches(
                "[a-z][a-z0-9-]{0,79}@[A-Za-z0-9][A-Za-z0-9._-]{0,39}"
            )) {
            throw new IllegalArgumentException(
                "Handler ID must use lowercase-name@version"
            );
        }
        return normalized;
    }
}
