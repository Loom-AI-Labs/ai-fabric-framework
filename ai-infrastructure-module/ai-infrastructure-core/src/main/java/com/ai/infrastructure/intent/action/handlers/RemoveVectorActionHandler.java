package com.ai.infrastructure.intent.action.handlers;

import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionConfirmation;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;
import com.ai.infrastructure.rag.VectorDatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.ai.infrastructure.config.condition.VectorDbConfiguredCondition;
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
