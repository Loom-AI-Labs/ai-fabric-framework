package ai.fabric.intent.action.connector;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;

import java.util.Map;

/**
 * Executes catalog actions that target MCP tools.
 */
public interface McpActionExecutor {

    String ERROR_MCP_TOOL_NOT_AVAILABLE = "MCP_TOOL_NOT_AVAILABLE";

    boolean isAvailable();

    ActionResult execute(String actionId,
                         ActionAccessMode accessMode,
                         Map<String, Object> params,
                         ActionContext context,
                         Map<String, Object> actionConfig);
}
