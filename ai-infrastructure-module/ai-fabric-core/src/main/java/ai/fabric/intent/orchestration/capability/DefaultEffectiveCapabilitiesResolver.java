package ai.fabric.intent.orchestration.capability;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionNames;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Default fail-closed capability intersection.
 */
public final class DefaultEffectiveCapabilitiesResolver implements EffectiveCapabilitiesResolver {

    @Override
    public EffectiveCapabilityProfile resolve(CapabilityResolutionRequest request) {
        Objects.requireNonNull(request, "request is required");
        RequestedCapabilityProfile requested = Objects.requireNonNull(
            request.requestedProfile(),
            "requestedProfile is required"
        );
        OrchestrationPolicy policy = request.orchestrationPolicy() != null
            ? request.orchestrationPolicy()
            : new OrchestrationPolicy(null, null, null, null, null, null, null, null);

        Map<String, AIActionMetaData> registered = registeredActions(request.registeredActions());
        Set<String> authorityAllowed = normalizeActions(request.authorityAllowedActions());
        Set<String> deploymentAllowed = normalizeActions(request.deploymentAllowedActions());

        Set<String> policyVisible = new LinkedHashSet<>();
        Set<String> policyReads = new LinkedHashSet<>();
        Set<String> policyWrites = new LinkedHashSet<>();
        for (Map.Entry<String, AIActionMetaData> entry : registered.entrySet()) {
            String action = entry.getKey();
            AIActionMetaData metadata = entry.getValue();
            if (!allowedByOptionalConstraint(action, authorityAllowed)
                || !allowedByOptionalConstraint(action, deploymentAllowed)) {
                continue;
            }
            if (metadata.getAccessMode() == ActionAccessMode.READ) {
                if (readAllowedByPolicy(action, metadata, policy)) {
                    policyVisible.add(action);
                    policyReads.add(action);
                }
            } else if (metadata.getAccessMode() != null
                && policy.capabilities().actionsEnabled()) {
                policyVisible.add(action);
                policyWrites.add(action);
            }
        }

        Set<String> visible = intersection(requested.visibleActions(), policyVisible);
        Set<String> reads = intersection(requested.requestableReadActions(), policyReads);
        reads.retainAll(visible);
        Set<String> writes = intersection(requested.proposableWriteActions(), policyWrites);
        writes.retainAll(visible);

        Set<String> vectorSpaces = resolveVectorSpaces(
            requested.requestedVectorSpaces(),
            request.registeredVectorSpaces(),
            policy.ragBudgets()
        );
        boolean retrievalEnabled = requested.retrievalEnabled()
            && policy.capabilities().retrievalEnabled();
        if (!retrievalEnabled) {
            vectorSpaces = Set.of();
        }
        OrchestrationPolicy.RagBudgets effectiveRagBudgets = effectiveRagBudgets(
            policy.ragBudgets(),
            retrievalEnabled,
            vectorSpaces
        );
        OrchestrationPolicy.ReadActionResolutionPolicy effectiveReadPolicy =
            effectiveReadPolicy(
                policy.readActionResolutionPolicy(),
                reads
            );

        String profile = policy.profile() != null ? policy.profile().name() : null;
        String hash = hash(
            profile,
            policy.mode(),
            retrievalEnabled,
            vectorSpaces,
            visible,
            reads,
            writes,
            effectiveRagBudgets,
            effectiveReadPolicy
        );
        return new EffectiveCapabilityProfile(
            profile,
            policy.mode(),
            retrievalEnabled,
            vectorSpaces,
            visible,
            reads,
            writes,
            effectiveRagBudgets,
            effectiveReadPolicy,
            hash
        );
    }

    /**
     * Resolves a profile equivalent to the current mode-only behavior.
     */
    public EffectiveCapabilityProfile resolveLegacy(
        OrchestrationPolicy policy,
        Collection<AIActionMetaData> registeredActions
    ) {
        Set<String> visible = new LinkedHashSet<>();
        Set<String> reads = new LinkedHashSet<>();
        Set<String> writes = new LinkedHashSet<>();
        if (registeredActions != null) {
            for (AIActionMetaData metadata : registeredActions) {
                if (metadata == null || metadata.getName() == null || metadata.getAccessMode() == null) {
                    continue;
                }
                String name = AIActionNames.normalize(metadata.getName());
                visible.add(name);
                if (metadata.getAccessMode() == ActionAccessMode.READ) {
                    reads.add(name);
                } else {
                    writes.add(name);
                }
            }
        }
        RequestedCapabilityProfile requested = new RequestedCapabilityProfile(
            true,
            policy != null && policy.ragBudgets() != null
                ? Set.copyOf(policy.ragBudgets().retrievalVectorSpacesAllowlist())
                : Set.of(),
            visible,
            reads,
            writes
        );
        return resolve(new CapabilityResolutionRequest(
            requested,
            policy,
            registeredActions,
            requested.requestedVectorSpaces(),
            Set.of(),
            Set.of(),
            null
        ));
    }

    private boolean readAllowedByPolicy(
        String action,
        AIActionMetaData metadata,
        OrchestrationPolicy policy
    ) {
        if (policy.capabilities().actionsEnabled()) {
            return true;
        }
        if (policy.capabilities().forceGroundingEligibleReadActionPostGeneration()
            && metadata.isGroundingEligible()) {
            return true;
        }
        OrchestrationPolicy.ReadActionResolutionPolicy readPolicy =
            policy.readActionResolutionPolicy();
        if (!readPolicy.enabled()) {
            return false;
        }
        if (!readPolicy.requireAllowlist()) {
            return true;
        }
        return readPolicy.allowedReadActions().contains(action);
    }

    private Set<String> resolveVectorSpaces(
        Set<String> requested,
        Set<String> registered,
        OrchestrationPolicy.RagBudgets budgets
    ) {
        Set<String> result = new LinkedHashSet<>(requested != null ? requested : Set.of());
        Set<String> registeredNormalized = normalizeSpaces(registered);
        if (!registeredNormalized.isEmpty()) {
            result.retainAll(registeredNormalized);
        }
        Set<String> allowlist = normalizeSpaces(
            budgets != null ? Set.copyOf(budgets.retrievalVectorSpacesAllowlist()) : Set.of()
        );
        if (!allowlist.isEmpty()) {
            result.retainAll(allowlist);
        }
        return result;
    }

    private OrchestrationPolicy.RagBudgets effectiveRagBudgets(
        OrchestrationPolicy.RagBudgets budgets,
        boolean retrievalEnabled,
        Set<String> vectorSpaces
    ) {
        OrchestrationPolicy.RagBudgets source = budgets != null
            ? budgets
            : OrchestrationPolicy.RagBudgets.defaults();
        List<String> allowlist = retrievalEnabled
            ? vectorSpaces.stream().sorted().toList()
            : List.of();
        Integer maxSpaces = source.maxSpaces();
        if (!allowlist.isEmpty()
            && (maxSpaces == null || maxSpaces > allowlist.size())) {
            maxSpaces = allowlist.size();
        }
        return new OrchestrationPolicy.RagBudgets(
            source.fanoutEnabled(),
            maxSpaces,
            source.topKPerSpace(),
            source.maxDocumentsReturnedToClient(),
            source.maxDocumentsUsedForContext(),
            source.maxContextChars(),
            allowlist,
            source.similarityThreshold()
        );
    }

    private OrchestrationPolicy.ReadActionResolutionPolicy effectiveReadPolicy(
        OrchestrationPolicy.ReadActionResolutionPolicy policy,
        Set<String> executableReadActions
    ) {
        OrchestrationPolicy.ReadActionResolutionPolicy source = policy != null
            ? policy
            : OrchestrationPolicy.ReadActionResolutionPolicy.defaults();
        boolean enabled = source.enabled() && !executableReadActions.isEmpty();
        return new OrchestrationPolicy.ReadActionResolutionPolicy(
            enabled,
            source.planningMode(),
            executableReadActions.stream().sorted().toList(),
            true,
            source.maxIterations(),
            source.maxActionsPerIteration(),
            source.maxTotalActions(),
            source.maxParallelActions(),
            source.maxPlannerContextChars(),
            source.maxActionEvidenceCharsPerAction(),
            source.ragCooperationMode(),
            source.requireGroundingEligible()
        );
    }

    private Map<String, AIActionMetaData> registeredActions(
        Collection<AIActionMetaData> actions
    ) {
        Map<String, AIActionMetaData> registered = new LinkedHashMap<>();
        if (actions == null) {
            return registered;
        }
        for (AIActionMetaData metadata : actions) {
            if (metadata != null && metadata.getName() != null) {
                registered.put(AIActionNames.normalize(metadata.getName()), metadata);
            }
        }
        return registered;
    }

    private boolean allowedByOptionalConstraint(String action, Set<String> constraint) {
        return constraint.isEmpty() || constraint.contains(action);
    }

    private Set<String> intersection(Set<String> requested, Set<String> allowed) {
        Set<String> result = new LinkedHashSet<>(requested != null ? requested : Set.of());
        result.retainAll(allowed);
        return result;
    }

    private Set<String> normalizeActions(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .map(AIActionNames::normalize)
                .forEach(normalized::add);
        }
        return normalized;
    }

    private Set<String> normalizeSpaces(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT))
                .forEach(normalized::add);
        }
        return normalized;
    }

    private String hash(
        String profile,
        String mode,
        boolean retrievalEnabled,
        Set<String> vectorSpaces,
        Set<String> visible,
        Set<String> reads,
        Set<String> writes,
        OrchestrationPolicy.RagBudgets ragBudgets,
        OrchestrationPolicy.ReadActionResolutionPolicy readPolicy
    ) {
        String canonical = String.join("|",
            String.valueOf(profile),
            String.valueOf(mode),
            Boolean.toString(retrievalEnabled),
            sorted(vectorSpaces),
            sorted(visible),
            sorted(reads),
            sorted(writes),
            canonical(ragBudgets),
            canonical(readPolicy)
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String sorted(Set<String> values) {
        List<String> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        return String.join(",", sorted);
    }

    private String canonical(OrchestrationPolicy.RagBudgets budgets) {
        return String.join(",",
            String.valueOf(budgets.fanoutEnabled()),
            String.valueOf(budgets.maxSpaces()),
            String.valueOf(budgets.topKPerSpace()),
            String.valueOf(budgets.maxDocumentsReturnedToClient()),
            String.valueOf(budgets.maxDocumentsUsedForContext()),
            String.valueOf(budgets.maxContextChars()),
            String.join(";", budgets.retrievalVectorSpacesAllowlist().stream().sorted().toList()),
            String.valueOf(budgets.similarityThreshold())
        );
    }

    private String canonical(OrchestrationPolicy.ReadActionResolutionPolicy policy) {
        return String.join(",",
            Boolean.toString(policy.enabled()),
            policy.planningMode().name(),
            String.join(";", policy.allowedReadActions().stream().sorted().toList()),
            Boolean.toString(policy.requireAllowlist()),
            Integer.toString(policy.maxIterations()),
            Integer.toString(policy.maxActionsPerIteration()),
            Integer.toString(policy.maxTotalActions()),
            Integer.toString(policy.maxParallelActions()),
            Integer.toString(policy.maxPlannerContextChars()),
            Integer.toString(policy.maxActionEvidenceCharsPerAction()),
            policy.ragCooperationMode().name(),
            Boolean.toString(policy.requireGroundingEligible())
        );
    }
}
