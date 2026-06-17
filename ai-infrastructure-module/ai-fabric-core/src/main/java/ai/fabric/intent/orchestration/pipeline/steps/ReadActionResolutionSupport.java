package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
final class ReadActionResolutionSupport {

    static final String METADATA_KEY = "readActionResolution";

    private ReadActionResolutionSupport() {
    }

    static boolean isActionExecutionAllowedByPolicy(String actionName,
                                                    AIActionMetaData metadata,
                                                    OrchestrationPolicy policy) {
        if (!StringUtils.hasText(actionName) || metadata == null || policy == null) {
            return false;
        }
        if (metadata.getAccessMode() != ActionAccessMode.READ || !metadata.isReadActionResolutionEligible()) {
            return false;
        }
        OrchestrationPolicy.ReadActionResolutionPolicy readPolicy = policy.readActionResolutionPolicy();
        if (readPolicy == null || !readPolicy.enabled()) {
            return false;
        }
        if (readPolicy.requireGroundingEligible() && !metadata.isGroundingEligible()) {
            return false;
        }
        if (!readPolicy.requireAllowlist()) {
            return true;
        }
        if (!readPolicy.hasAllowedReadActions()) {
            return false;
        }
        String normalizedActionName = actionName.trim().toLowerCase(Locale.ROOT);
        return readPolicy.allowedReadActions().stream()
            .filter(StringUtils::hasText)
            .map(value -> value.trim().toLowerCase(Locale.ROOT))
            .anyMatch(normalizedActionName::equals);
    }

    static ReadActionResolutionService.ResolutionOutcome resolve(
        ObjectProvider<ReadActionResolutionService> serviceProvider,
        Intent intent,
        OrchestrationContext context,
        PipelineContext pipelineContext,
        Map<String, Object> metadata
    ) {
        ReadActionResolutionService service = serviceProvider != null
            ? serviceProvider.getIfAvailable()
            : null;
        if (service == null) {
            return ReadActionResolutionService.ResolutionOutcome.skipped("SERVICE_UNAVAILABLE");
        }
        try {
            ReadActionResolutionService.ResolutionOutcome outcome = service.resolve(intent, context, pipelineContext);
            if (metadata != null && outcome != null && outcome.diagnostics() != null && !outcome.diagnostics().isEmpty()) {
                metadata.put(METADATA_KEY, outcome.diagnostics());
            }
            return outcome != null
                ? outcome
                : ReadActionResolutionService.ResolutionOutcome.skipped("NO_RESULT");
        } catch (Exception ex) {
            log.warn("Read-action resolution failed for request {}: {}",
                pipelineContext != null ? pipelineContext.getRequestId() : "unknown",
                ex.getMessage(),
                ex);
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("attempted", false);
            diagnostics.put("skipReason", "ERROR");
            diagnostics.put("message", ex.getMessage());
            if (metadata != null) {
                metadata.put(METADATA_KEY, Collections.unmodifiableMap(diagnostics));
            }
            return ReadActionResolutionService.ResolutionOutcome.skipped("ERROR");
        }
    }

    static OrchestrationResult attachDiagnostics(OrchestrationResult result,
                                                 ReadActionResolutionService.ResolutionOutcome resolutionOutcome) {
        if (result == null
            || resolutionOutcome == null
            || resolutionOutcome.diagnostics() == null
            || resolutionOutcome.diagnostics().isEmpty()) {
            return result;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result.getMetadata() != null && !result.getMetadata().isEmpty()) {
            metadata.putAll(result.getMetadata());
        }
        metadata.put(METADATA_KEY, Collections.unmodifiableMap(new LinkedHashMap<>(resolutionOutcome.diagnostics())));
        result.setMetadata(Collections.unmodifiableMap(metadata));

        Map<String, Object> data = new LinkedHashMap<>();
        if (result.getData() != null && !result.getData().isEmpty()) {
            data.putAll(result.getData());
        }
        data.put(METADATA_KEY, Collections.unmodifiableMap(new LinkedHashMap<>(resolutionOutcome.diagnostics())));
        result.setData(Collections.unmodifiableMap(data));
        return result;
    }

    static String mergeEvidenceIntoGenerationContext(String retrievedContext,
                                                     PipelineContext pipelineContext,
                                                     ReadActionResolutionService.ResolutionOutcome resolutionOutcome,
                                                     String noContextMessage) {
        String combinedContext = retrievedContext;
        if (resolutionOutcome != null && StringUtils.hasText(resolutionOutcome.evidenceContext())) {
            String readActionEvidence = evidenceGenerationContext(resolutionOutcome.evidenceContext());
            if (!StringUtils.hasText(combinedContext) || noContextMessage.equals(combinedContext)) {
                combinedContext = readActionEvidence;
            } else {
                combinedContext = readActionEvidence + "\n\n" + combinedContext;
            }
        }
        return RagContextSupport.prependPinnedTargetsContext(combinedContext, pipelineContext);
    }

    static String evidenceGenerationContext(String evidenceContext) {
        if (!StringUtils.hasText(evidenceContext)) {
            return evidenceContext;
        }
        return """
            READ ACTION EVIDENCE POLICY
            - Treat the read-action evidence below as live action output from configured systems.
            - Use read-action evidence as the source of truth for fields it explicitly contains when retrieved context omits or conflicts with those fields.
            - Mention names, identifiers, numeric values, statuses, and other facts only when the exact fact is explicitly present in the read-action evidence or retrieved context.
            - If list/search/relationship evidence returns multiple records or a count greater than one, do not state that only one record exists; summarize the relevant returned records and then state any missing evidence.
            - If read actions found no records for a requested fact, state that the fact is not available from the live evidence.
            - If a named lookup failed or returned no matching record, do not answer using similarly named records, generic documents, or unrelated context; state that the named record is not present in the live evidence.
            - Do not expose implementation wording such as upstream failure, HTTP status, error code, or action failure; translate failed lookups into user-facing missing live evidence.
            - Do not use unrelated documents as entity-specific evidence unless the evidence explicitly links them to the requested entity and claim.
            - Do not provide handoffs, next steps, or support references unless they are explicitly present in the evidence.
            - Do not append generic closers.

            %s
            """.formatted(evidenceContext);
    }
}
