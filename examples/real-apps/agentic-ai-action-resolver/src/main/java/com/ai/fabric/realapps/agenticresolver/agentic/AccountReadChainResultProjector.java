package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainResultProjection;
import ai.fabric.execution.chain.SpecialistChainTargetResultProjector;
import ai.fabric.execution.gateway.AIExecutionResult;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

public final class AccountReadChainResultProjector
    implements SpecialistChainTargetResultProjector<
        AccountDelegationCoordinatorRequest,
        AccountResolutionResult
    > {

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("account-chain-read-result", "1");

    @Override
    public SpecialistChainComponentId id() {
        return ID;
    }

    @Override
    public Class<AccountDelegationCoordinatorRequest> chainRequestType() {
        return AccountDelegationCoordinatorRequest.class;
    }

    @Override
    public Class<AccountResolutionResult> targetOutputType() {
        return AccountResolutionResult.class;
    }

    @Override
    public SpecialistChainResultProjection project(
        AccountDelegationCoordinatorRequest request,
        AIExecutionResult<AccountResolutionResult> execution
    ) {
        AccountResolutionResult output = execution.output();
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("assessment", output.assessment().name());
        facts.put("blockerCount", Integer.toString(output.blockers().size()));
        facts.put(
            "blockerRequirements",
            output.blockers().stream()
                .map(blocker -> blocker.requirement().name())
                .collect(Collectors.joining(","))
        );
        facts.put(
            "recommendedNextSteps",
            output.blockers().stream()
                .map(AccountResolutionResult.Blocker::recommendedNextStep)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(" | "))
        );
        return new SpecialistChainResultProjection(
            output.summary(),
            facts,
            execution.evidence().stream()
                .map(AIEvidenceReference::evidenceId)
                .distinct()
                .toList()
        );
    }
}
