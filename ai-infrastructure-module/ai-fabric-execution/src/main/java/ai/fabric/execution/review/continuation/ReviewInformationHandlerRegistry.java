package ai.fabric.execution.review.continuation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReviewInformationHandlerRegistry {

    private final Map<String, ReviewInformationHandler> handlers;

    public ReviewInformationHandlerRegistry(
        List<ReviewInformationHandler> values
    ) {
        Map<String, ReviewInformationHandler> loaded = new LinkedHashMap<>();
        for (ReviewInformationHandler value :
            values == null ? List.<ReviewInformationHandler>of() : values) {
            Objects.requireNonNull(value, "handler must not be null");
            String id = exactId(value.id());
            if (loaded.putIfAbsent(id, value) != null) {
                throw new IllegalStateException(
                    "Duplicate review information handler: " + id
                );
            }
        }
        this.handlers = Map.copyOf(loaded);
    }

    public ReviewInformationHandler require(String id) {
        ReviewInformationHandler handler = handlers.get(exactId(id));
        if (handler == null) {
            throw new IllegalArgumentException(
                "Review information handler is not registered: " + id
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
