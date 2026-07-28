package ai.fabric.execution.gateway;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.capability.CapabilityResolutionRequest;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the server-authoritative capability intersection for a specialist.
 */
public final class SpecialistCapabilityResolver {

    private final EffectiveCapabilitiesResolver capabilitiesResolver;
    private final AIActionRegistry actionRegistry;
    private final ExecutionCapabilityInventory capabilityInventory;
    private final SpecialistAuthorityResolver authorityResolver;

    public SpecialistCapabilityResolver(
        EffectiveCapabilitiesResolver capabilitiesResolver,
        AIActionRegistry actionRegistry,
        ExecutionCapabilityInventory capabilityInventory,
        SpecialistAuthorityResolver authorityResolver
    ) {
        this.capabilitiesResolver = Objects.requireNonNull(
            capabilitiesResolver,
            "capabilitiesResolver is required"
        );
        this.actionRegistry = Objects.requireNonNull(
            actionRegistry,
            "actionRegistry is required"
        );
        this.capabilityInventory = Objects.requireNonNull(
            capabilityInventory,
            "capabilityInventory is required"
        );
        this.authorityResolver = Objects.requireNonNull(
            authorityResolver,
            "authorityResolver is required"
        );
    }

    public EffectiveCapabilityProfile resolve(
        SpecialistDefinition<?, ?> definition,
        PipelineContext preflight,
        TrustedExecutionContext trustedContext
    ) {
        Objects.requireNonNull(definition, "definition is required");
        Objects.requireNonNull(preflight, "preflight is required");
        SpecialistAuthority authority;
        try {
            authority = authorityResolver.resolve(
                definition,
                Objects.requireNonNull(
                    trustedContext,
                    "trustedContext is required"
                )
            );
        } catch (
            DefaultSpecialistAuthorityResolver.AuthorityDeniedException ex
        ) {
            throw new SpecialistCapabilityResolutionException(
                ex.reason(),
                ex.getMessage()
            );
        }
        RequestedCapabilityProfile requested =
            definition.executionProfile().requestedCapabilities();

        if (!authority.allowedActions().containsAll(requested.visibleActions())) {
            throw new SpecialistCapabilityResolutionException(
                "ACTION_AUTHORITY_INTERSECTION_FAILED",
                "The trusted caller is not authorized for every requested action."
            );
        }
        if (requested.retrievalEnabled()) {
            if (!authority.allowedVectorSpaces()
                .containsAll(requested.requestedVectorSpaces())) {
                throw new SpecialistCapabilityResolutionException(
                    "VECTOR_AUTHORITY_INTERSECTION_FAILED",
                    "The trusted caller is not authorized for every requested vector space."
                );
            }
            if (!normalize(capabilityInventory.registeredVectorSpaces())
                .containsAll(requested.requestedVectorSpaces())) {
                throw new SpecialistCapabilityResolutionException(
                    "VECTOR_SPACE_NOT_REGISTERED",
                    "A requested vector space is not registered in this deployment."
                );
            }
        }

        Set<String> authorizedRegisteredSpaces = new LinkedHashSet<>(
            normalize(capabilityInventory.registeredVectorSpaces())
        );
        authorizedRegisteredSpaces.retainAll(authority.allowedVectorSpaces());
        EffectiveCapabilityProfile effective = capabilitiesResolver.resolve(
            new CapabilityResolutionRequest(
                requested,
                preflight.getOrchestrationPolicy(),
                actionRegistry.getAllMetadata(),
                authorizedRegisteredSpaces,
                authority.allowedActions(),
                capabilityInventory.deploymentAllowedActions(),
                null
            )
        );
        if (!effective.visibleActions().containsAll(requested.visibleActions())
            || !effective.executableReadActions()
                .containsAll(requested.requestableReadActions())
            || !effective.proposableWriteActions()
                .containsAll(requested.proposableWriteActions())
            || (requested.retrievalEnabled()
                && !effective.effectiveVectorSpaces()
                    .containsAll(requested.requestedVectorSpaces()))) {
            throw new SpecialistCapabilityResolutionException(
                "EFFECTIVE_CAPABILITY_INTERSECTION_FAILED",
                "Mode, deployment, or authority policy denied a requested capability."
            );
        }
        validateStrategy(definition, preflight);
        return effective;
    }

    private void validateStrategy(
        SpecialistDefinition<?, ?> definition,
        PipelineContext preflight
    ) {
        if (definition.executionProfile().strategy()
            != ExecutionStrategy.BOUNDED_ITERATIVE) {
            return;
        }
        var readPolicy =
            preflight.getOrchestrationPolicy().readActionResolutionPolicy();
        if (readPolicy == null
            || !readPolicy.enabled()
            || readPolicy.planningMode()
                != ai.fabric.config.OrchestrationProperties
                    .ReadActionResolutionPlanningMode.ITERATIVE) {
            throw new SpecialistCapabilityResolutionException(
                "ITERATIVE_MODE_REQUIRED",
                "BOUNDED_ITERATIVE requires an iterative read-action Mode."
            );
        }
    }

    private Set<String> normalize(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.toLowerCase(Locale.ROOT))
            .forEach(normalized::add);
        return Set.copyOf(normalized);
    }
}
