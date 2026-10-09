package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionTargetRef;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.information.ReadActionExecutionScope;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
final class ReadActionResolutionSupport {

    static final String METADATA_KEY = "readActionResolution";
    static final String ACTIONS_KEY = "actions";

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
        return resolve(serviceProvider, intent, context, pipelineContext, metadata, null);
    }

    static ReadActionResolutionService.ResolutionOutcome resolve(
        ObjectProvider<ReadActionResolutionService> serviceProvider,
        Intent intent,
        OrchestrationContext context,
        PipelineContext pipelineContext,
        Map<String, Object> metadata,
        ReadActionExecutionScope executionScope
    ) {
        ReadActionResolutionService service = serviceProvider != null
            ? serviceProvider.getIfAvailable()
            : null;
        if (service == null) {
            return ReadActionResolutionService.ResolutionOutcome.skipped("SERVICE_UNAVAILABLE");
        }
        try {
            ReadActionResolutionService.ResolutionOutcome outcome = executionScope != null
                ? service.resolve(intent, context, pipelineContext, executionScope)
                : service.resolve(intent, context, pipelineContext);
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
        if (result == null || resolutionOutcome == null) {
            return result;
        }

        result.setInternalPinnedTargets(collectInternalPinnedTargets(result, resolutionOutcome));
        if (resolutionOutcome.diagnostics() == null || resolutionOutcome.diagnostics().isEmpty()) {
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
        List<Object> actionResults = projectActionResults(resolutionOutcome);
        if (!actionResults.isEmpty() && firstList(data.get(ACTIONS_KEY)).isEmpty()) {
            data.put(ACTIONS_KEY, actionResults);
        }
        result.setData(Collections.unmodifiableMap(data));
        return result;
    }

    private static List<Object> projectActionResults(ReadActionResolutionService.ResolutionOutcome resolutionOutcome) {
        if (resolutionOutcome == null
            || resolutionOutcome.executedActions() == null
            || resolutionOutcome.executedActions().isEmpty()) {
            return List.of();
        }

        List<Object> actions = new ArrayList<>();
        for (ReadActionResolutionService.ExecutedReadAction executed : resolutionOutcome.executedActions()) {
            if (executed == null || executed.actionResult() == null || !StringUtils.hasText(executed.actionName())) {
                continue;
            }

            ActionResult source = executed.actionResult();
            ActionResult publicResult = ActionResult.builder()
                .success(source.isSuccess())
                .message(source.getMessage())
                .data(source.getData())
                .groundingSufficiency(source.getGroundingSufficiency())
                .errorCode(source.getErrorCode())
                .build();

            Map<String, Object> action = new LinkedHashMap<>();
            action.put("action", executed.actionName());
            action.put("actionResult", publicResult);
            if (StringUtils.hasText(executed.actionExecutionId())) {
                action.put("actionExecutionId", executed.actionExecutionId());
            }
            actions.add(Collections.unmodifiableMap(action));
        }
        return actions.isEmpty() ? List.of() : List.copyOf(actions);
    }

    private static List<ActionTargetRef> collectInternalPinnedTargets(
        OrchestrationResult result,
        ReadActionResolutionService.ResolutionOutcome resolutionOutcome
    ) {
        final int maxTargetsPerTurn = 100;
        LinkedHashMap<String, ActionTargetRef> targets = new LinkedHashMap<>();
        if (result.getInternalPinnedTargets() != null) {
            for (ActionTargetRef target : result.getInternalPinnedTargets()) {
                addInternalPinnedTarget(targets, target, maxTargetsPerTurn);
            }
        }
        if (resolutionOutcome.executedActions() != null) {
            for (ReadActionResolutionService.ExecutedReadAction executed : resolutionOutcome.executedActions()) {
                if (executed == null || executed.actionResult() == null || !executed.actionResult().isSuccess()
                    || executed.actionResult().getPinnedTargets() == null) {
                    continue;
                }
                for (ActionTargetRef target : executed.actionResult().getPinnedTargets()) {
                    addInternalPinnedTarget(targets, target, maxTargetsPerTurn);
                    if (targets.size() >= maxTargetsPerTurn) {
                        break;
                    }
                }
                if (targets.size() >= maxTargetsPerTurn) {
                    break;
                }
            }
        }
        return targets.isEmpty() ? List.of() : List.copyOf(targets.values());
    }

    private static void addInternalPinnedTarget(Map<String, ActionTargetRef> targets,
                                                ActionTargetRef target,
                                                int maxTargets) {
        if (target == null || targets.size() >= maxTargets || !StringUtils.hasText(target.id())) {
            return;
        }
        String vectorSpace = StringUtils.hasText(target.vectorSpace()) ? target.vectorSpace().trim() : "";
        String id = target.id().trim();
        targets.putIfAbsent(vectorSpace + "\u0000" + id, target);
    }

    private static List<?> firstList(Object value) {
        return value instanceof List<?> list ? list : List.of();
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
