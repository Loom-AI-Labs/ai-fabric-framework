package com.ai.infrastructure.intent.action.handlers;

import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionConfirmation;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.rag.VectorDatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.ai.infrastructure.config.condition.VectorDbConfiguredCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Conditional(VectorDbConfiguredCondition.class)
@ConditionalOnProperty(prefix = "ai.actions.builtin.vector-management", name = "enabled", havingValue = "true")
@AIAction(
    name = "clear_vector_index",
    description = "Remove all vectors from the configured vector database.",
    category = "vector",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
public class ClearVectorIndexActionHandler {

    private final VectorDatabaseService vectorDatabaseService;

    @ActionConfirmation
    public String confirm() {
        return "This will permanently delete all indexed vectors. Continue?";
    }

    @ActionExecute
    public ActionResult execute(ActionContext context) {
        long removed = vectorDatabaseService.clearVectors();
        log.info("Cleared {} vectors from the vector database (requested by user={})", removed, context != null ? context.identifier() : "unknown");
        return ActionResult.builder()
            .success(true)
            .message(removed == 0 ? "Vector index already empty." : "Cleared " + removed + " vectors.")
            .data(ActionResultContracts.object(Map.of("removed", removed)))
            .build();
    }
}
