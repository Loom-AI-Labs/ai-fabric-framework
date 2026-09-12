package com.ai.fabric.realapps.incident.service;

import com.ai.fabric.realapps.incident.domain.ChangeRiskInvestigationFinding;
import com.ai.fabric.realapps.incident.domain.IncidentSpecialistTrace;
import com.ai.fabric.realapps.incident.domain.ServiceHealthInvestigationFinding;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class IncidentDecisionTraceStore {

    private static final int MAX_TRACES = 2000;
    private final Map<String, IncidentSpecialistTrace> traces =
        new ConcurrentHashMap<>();

    public void record(
        String invocationId,
        String specialist,
        ServiceHealthInvestigationFinding finding
    ) {
        put(invocationId, new IncidentSpecialistTrace(
            specialist,
            "SUCCEEDED",
            finding.dataSources(),
            finding.evidenceIds(),
            java.util.List.of(),
            finding.sourceRevision(),
            "ACTION_CITATIONS_VALIDATED"
        ));
    }

    public void record(
        String invocationId,
        String specialist,
        ChangeRiskInvestigationFinding finding
    ) {
        put(invocationId, new IncidentSpecialistTrace(
            specialist,
            "SUCCEEDED",
            finding.dataSources(),
            finding.evidenceIds(),
            finding.runbookEvidenceIds(),
            finding.sourceRevision(),
            "ACTION_AND_RAG_CITATIONS_VALIDATED"
        ));
    }

    public IncidentSpecialistTrace find(String invocationId) {
        return invocationId == null ? null : traces.get(invocationId);
    }

    private void put(String invocationId, IncidentSpecialistTrace trace) {
        if (invocationId == null || invocationId.isBlank()) {
            return;
        }
        if (traces.size() >= MAX_TRACES) {
            traces.clear();
        }
        traces.put(invocationId, trace);
    }
}
