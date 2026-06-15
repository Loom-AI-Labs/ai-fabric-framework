package ai.fabric.intent.action.handlers;

import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import ai.fabric.rag.VectorDatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ai.fabric.config.condition.VectorDbConfiguredCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.StringUtils;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Conditional(VectorDbConfiguredCondition.class)
@ConditionalOnProperty(prefix = "ai.actions.builtin.vector-management", name = "enabled", havingValue = "true")
@AIAction(
    name = "remove_vector",
    description = "Remove a single vector from the vector database by entity type and id.",
    category = "vector",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
public class RemoveVectorActionHandler {

    private final VectorDatabaseService vectorDatabaseService;

    @ActionConfirmation
    public String confirm(@Param(required = true, description = "Entity type") String entityType,
                          @Param(required = true, description = "Entity id") String entityId) {
        return "Remove vector for entity '" + entityType + ":" + entityId + "'?";
    }

    @ActionExecute
    public ActionResult execute(@Param(required = true, description = "Entity type") String entityType,
                                @Param(required = true, description = "Entity id") String entityId,
                                ActionContext context) {
        boolean removed = vectorDatabaseService.removeVector(entityType, entityId);
        log.info("Remove vector request entityType={} entityId={} user={} removed={}",
            entityType, entityId, context != null ? context.identifier() : "unknown", removed);
        return ActionResult.builder()
            .success(removed)
            .message(removed ? "Vector removed." : "Vector not found.")
            .data(ActionResultContracts.object(Map.of(
                "entityType", entityType,
                "entityId", entityId,
                "removed", removed
            )))
            .build();
    }
}
