package ai.fabric.execution.gateway;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.intent.action.AIActionNames;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Exact-scope authority resolver. No wildcard or declaration-based grant is supported.
 */
public final class DefaultSpecialistAuthorityResolver
    implements SpecialistAuthorityResolver {

    @Override
    public SpecialistAuthority resolve(
        SpecialistDefinition<?, ?> definition,
        TrustedExecutionContext trustedContext
    ) {
        Objects.requireNonNull(definition, "definition is required");
        Objects.requireNonNull(trustedContext, "trustedContext is required");
        Set<String> scopes = trustedContext.grantedScopes().stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(scope -> !scope.isEmpty())
            .map(scope -> scope.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String specialistNameScope =
            "specialist:" + definition.id().name().toLowerCase(Locale.ROOT);
        String specialistVersionScope =
            "specialist:" + definition.id().toString().toLowerCase(Locale.ROOT);
        if (!scopes.contains(specialistNameScope)
            && !scopes.contains(specialistVersionScope)) {
            throw new AuthorityDeniedException(
                "SPECIALIST_SCOPE_REQUIRED",
                "The trusted caller is not authorized for this specialist."
            );
        }

        Set<String> actions = new LinkedHashSet<>();
        Set<String> vectorSpaces = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope.startsWith("action:") && scope.length() > "action:".length()) {
                actions.add(AIActionNames.normalize(scope.substring("action:".length())));
            }
            if (scope.startsWith("vector:") && scope.length() > "vector:".length()) {
                vectorSpaces.add(
                    scope.substring("vector:".length()).trim().toLowerCase(Locale.ROOT)
                );
            }
        }
        return new SpecialistAuthority(actions, vectorSpaces);
    }

    public static final class AuthorityDeniedException extends RuntimeException {
        private final String reason;

        public AuthorityDeniedException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
