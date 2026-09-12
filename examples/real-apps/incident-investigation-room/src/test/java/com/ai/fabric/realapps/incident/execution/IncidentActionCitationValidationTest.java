package com.ai.fabric.realapps.incident.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.specialist.manifest.SpecialistFinalOutputValidationContext;
import ai.fabric.execution.specialist.manifest.SpecialistOutputNormalizationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IncidentActionCitationValidationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IncidentActionCitationValidation validation =
        new IncidentActionCitationValidation(objectMapper);

    @Test
    void acceptsExactSubsetOfCanonicalActionAndRunbookEvidence() throws Exception {
        var context = context(
            "read_recent_deployments",
            List.of("change-1", "change-distractor"),
            """
                {
                  "riskLevel":"HIGH",
                  "suspectedChange":"release 1",
                  "summary":"Release 1 is temporally relevant.",
                  "evidenceIds":["change-1"],
                  "runbookEvidenceIds":["runbook-1"],
                  "dataSources":[{
                    "action":"read_recent_deployments",
                    "candidateCount":2,
                    "groundingUsable":true
                  }],
                  "candidateEventCount":2,
                  "selectionReason":"The selected release matches the incident window.",
                  "sourceRevision":"revision-1"
                }
                """,
            List.of(runbook("runbook-1"))
        );

        assertThatCode(() -> validation.validateChange(context))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownEventEvenWhenUserCouldHaveNamedIt() throws Exception {
        var context = context(
            "read_service_metrics",
            List.of("metric-1", "metric-2"),
            """
                {
                  "healthStatus":"DEGRADED",
                  "severity":"HIGH",
                  "summary":"A metric is degraded.",
                  "evidenceIds":["other-tenant-critical-error"],
                  "dataSources":[{
                    "action":"read_service_metrics",
                    "candidateCount":2,
                    "groundingUsable":true
                  }],
                  "candidateEventCount":2,
                  "selectionReason":"Named by untrusted input.",
                  "sourceRevision":"revision-1"
                }
                """,
            List.of()
        );

        assertThatThrownBy(() -> validation.validateService(context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("outside executed action evidence");
    }

    @Test
    void rejectsInventedRunbook() throws Exception {
        var inventedRunbook = context(
            "read_recent_deployments",
            List.of("change-1"),
            changeOutput(1, "runbook-private"),
            List.of(runbook("runbook-1"))
        );
        assertThatThrownBy(() -> validation.validateChange(inventedRunbook))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("outside retrieved evidence");
    }

    @Test
    void canonicalizesCountsAndRevisionFromExecutedActions() throws Exception {
        var modelOutput = context(
            "read_recent_deployments",
            List.of("change-1"),
            changeOutput(2, "runbook-1"),
            List.of(runbook("runbook-1"))
        );
        var normalized = validation.normalizeChange(
            new SpecialistOutputNormalizationContext(
                modelOutput.output(),
                modelOutput.sourceResult(),
                modelOutput.evidence()
            )
        );

        assertThat(normalized.path("candidateEventCount").asInt()).isOne();
        assertThat(normalized.at("/dataSources/0/candidateCount").asInt())
            .isOne();
        assertThat(normalized.path("sourceRevision").asText())
            .isEqualTo("revision-1");
        assertThatCode(() -> validation.validateChange(
            new SpecialistFinalOutputValidationContext(
                normalized,
                modelOutput.sourceResult(),
                modelOutput.evidence()
            )
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateEvidenceSelection() throws Exception {
        var duplicate = context(
            "read_service_metrics",
            List.of("metric-1", "metric-2"),
            """
                {
                  "healthStatus":"DEGRADED",
                  "severity":"HIGH",
                  "summary":"A metric is degraded.",
                  "evidenceIds":["metric-1","metric-1"],
                  "dataSources":[{
                    "action":"read_service_metrics",
                    "candidateCount":2,
                    "groundingUsable":true
                  }],
                  "candidateEventCount":2,
                  "selectionReason":"The metric matches the incident window.",
                  "sourceRevision":"revision-1"
                }
                """,
            List.of()
        );

        assertThatThrownBy(() -> validation.validateService(duplicate))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not contain duplicates");
    }

    private SpecialistFinalOutputValidationContext context(
        String action,
        List<String> candidateIds,
        String output,
        List<AIEvidenceReference> evidence
    ) throws Exception {
        String facts = objectMapper.writeValueAsString(Map.of(
            "action", action,
            "sourceRevision", "revision-1",
            "candidateEventIds", candidateIds
        ));
        OrchestrationResult source = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .data(Map.of(
                "readActionResolution",
                Map.of("executedActions", List.of(Map.of(
                    "action", action,
                    "groundingUsable", true,
                    "evidenceSummary", facts
                )))
            ))
            .build();
        return new SpecialistFinalOutputValidationContext(
            objectMapper.readTree(output),
            source,
            evidence
        );
    }

    private String changeOutput(int count, String runbook) {
        return """
            {
              "riskLevel":"HIGH",
              "suspectedChange":"release 1",
              "summary":"Release 1 is temporally relevant.",
              "evidenceIds":["change-1"],
              "runbookEvidenceIds":["%s"],
              "dataSources":[{
                "action":"read_recent_deployments",
                "candidateCount":%d,
                "groundingUsable":true
              }],
              "candidateEventCount":%d,
              "selectionReason":"The release matches the incident window.",
              "sourceRevision":"revision-1"
            }
            """.formatted(runbook, count, count);
    }

    private AIEvidenceReference runbook(String id) {
        return new AIEvidenceReference(
            id,
            "Approved runbook guidance",
            0.9,
            "incident-runbook",
            null,
            "incident-runbook",
            Map.of()
        );
    }
}
