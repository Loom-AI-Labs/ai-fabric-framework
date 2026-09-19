package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainLimits;
import java.util.List;
import java.util.Set;

/**
 * Safe deployment inventory for trusted specialist-chain authoring tools.
 * This catalogue is descriptive and grants no runtime authority.
 */
public record SpecialistChainAuthoringCatalog(
    String resourceContract,
    boolean featureEnabled,
    Set<SpecialistChainMappingSource> mappingSources,
    Set<SpecialistChainResultProjectionType> projectionTypes,
    Set<SpecialistChainEvidencePolicy> evidencePolicies,
    SpecialistChainDeclarativeBounds declarativeBounds,
    SpecialistChainLimits deploymentCeilings,
    List<SpecialistOption> specialists
) {
    public SpecialistChainAuthoringCatalog {
        resourceContract = "ai.fabric/v1";
        mappingSources = Set.copyOf(mappingSources);
        projectionTypes = Set.copyOf(projectionTypes);
        evidencePolicies = Set.copyOf(evidencePolicies);
        specialists = specialists == null ? List.of() : List.copyOf(specialists);
    }

    public record SpecialistOption(
        String id,
        String contentHash,
        String inputSchema,
        String outputSchema,
        boolean managerContractCandidate,
        boolean readOnly,
        boolean nonInteractive,
        boolean schemaBackedJson
    ) {}
}
