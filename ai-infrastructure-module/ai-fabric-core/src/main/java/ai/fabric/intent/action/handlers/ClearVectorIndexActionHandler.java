package ai.fabric.intent.action.handlers;

import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.rag.VectorDatabaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ai.fabric.config.condition.VectorDbConfiguredCondition;
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
