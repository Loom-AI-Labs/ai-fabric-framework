package ai.fabric.indexing.api;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable invocation data exposed to an application target resolver.
 */
public record AIProcessInvocation(
    Method method,
    Object service,
    List<Object> arguments,
    Object result,
    AIProcessOperation operation,
    String declaredEntityType
) {
    public AIProcessInvocation {
        Objects.requireNonNull(method, "method is required");
        Objects.requireNonNull(operation, "operation is required");
        arguments = arguments == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(arguments));
        declaredEntityType = declaredEntityType == null ? "" : declaredEntityType.trim();
    }
}
