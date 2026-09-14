package com.ai.fabric.realapps.incident.execution;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainResultProjection;
import ai.fabric.execution.chain.SpecialistChainTargetResultProjector;
import ai.fabric.execution.gateway.AIExecutionResult;
import com.ai.fabric.realapps.incident.domain.IncidentDataSourceUsage;
import com.ai.fabric.realapps.incident.domain.IncidentManagerRequest;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationFinding;
import java.util.LinkedHashMap;

public final class IncidentServiceChainResultProjector
    implements SpecialistChainTargetResultProjector<IncidentManagerRequest, ServiceHealthInvestigationFinding> {

    public static final SpecialistChainComponentId ID =
        SpecialistChainComponentId.of("incident-chain-service-result", "1");

    @Override
    public SpecialistChainComponentId id() {
        return ID;
    }

    @Override
    public Class<IncidentManagerRequest> chainRequestType() {
        return IncidentManagerRequest.class;
    }

    @Override
    public Class<ServiceHealthInvestigationFinding> targetOutputType() {
        return ServiceHealthInvestigationFinding.class;
    }

    @Override
    public SpecialistChainResultProjection project(
        IncidentManagerRequest request,
        AIExecutionResult<ServiceHealthInvestigationFinding> execution
    ) {
        ServiceHealthInvestigationFinding finding = execution.output();
        LinkedHashMap<String, String> facts = new LinkedHashMap<>();
        facts.put("healthStatus", finding.healthStatus());
        facts.put("severity", finding.severity());
        facts.put(
            "candidateEventCount",
            Integer.toString(finding.candidateEventCount())
        );
        facts.put("selectedEventIds", String.join(",", finding.evidenceIds()));
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
