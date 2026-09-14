package com.ai.fabric.realapps.incident.execution;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainResultProjection;
import ai.fabric.execution.chain.SpecialistChainTargetResultProjector;
import ai.fabric.execution.gateway.AIExecutionResult;
import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationFinding;
import com.ai.fabric.realapps.incident.domain.IncidentDataSourceUsage;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import java.util.LinkedHashMap;

public final class IncidentChangeChainResultProjector
    implements SpecialistChainTargetResultProjector<IncidentManagerRequest, ChangeRiskInvestigationFinding> {

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("incident-chain-change-result", "1");

    @Override
    public SpecialistChainComponentId id() {
        return ID;
    }

    @Override
    public Class<IncidentManagerRequest> chainRequestType() {
        return IncidentManagerRequest.class;
    }

    @Override
    public Class<ChangeRiskInvestigationFinding> targetOutputType() {
        return ChangeRiskInvestigationFinding.class;
    }

    @Override
    public SpecialistChainResultProjection project(
        IncidentManagerRequest request,
        AIExecutionResult<ChangeRiskInvestigationFinding> execution
    ) {
        ChangeRiskInvestigationFinding finding = execution.output();
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("riskLevel", finding.riskLevel());
        facts.put("suspectedChange", finding.suspectedChange());
        facts.put(
            "candidateEventCount",
            Integer.toString(finding.candidateEventCount())
        );
        facts.put("selectedEventIds", String.join(",", finding.evidenceIds()));
        facts.put(
            "runbookEvidenceIds",
            String.join(",", finding.runbookEvidenceIds())
        );
        facts.put(
            "dataSources",
            finding.dataSources().stream()
                .map(IncidentDataSourceUsage::action)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(","))
        );
        facts.put("sourceRevision", finding.sourceRevision());
        return new SpecialistChainResultProjection(
            finding.summary(),
            facts,
            execution.evidence().stream()
                .map(AIEvidenceReference::evidenceId)
                .distinct()
                .toList()
        );
    }
}
