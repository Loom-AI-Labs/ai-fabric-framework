package ai.fabric.intent.action.invocation;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.capability.CapabilityAwareActionCatalog;
import ai.fabric.intent.orchestration.capability.CapabilityAwareActionCatalog.CapabilityDeniedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.util.StringUtils;

/**
 * Default implementation of the final governed action boundary.
 */
public final class DefaultGovernedActionInvocationService
    implements GovernedActionInvocationService {

    private final Function<String, Optional<AIActionHandler>> handlerLookup;
    private final Function<String, Optional<AIActionMetaData>> metadataLookup;
    private final CapabilityAwareActionCatalog actionCatalog;

    public DefaultGovernedActionInvocationService(AIActionRegistry actionRegistry) {
        AIActionRegistry registry = java.util.Objects.requireNonNull(
            actionRegistry,
            "actionRegistry is required"
        );
        this.handlerLookup = registry::findHandler;
        this.metadataLookup = registry::findMetadata;
        this.actionCatalog = new CapabilityAwareActionCatalog(registry);
    }

    /**
     * Compatibility constructor for the existing direct callback factory API.
     */
    public DefaultGovernedActionInvocationService(AIActionHandler handler) {
        AIActionHandler required = java.util.Objects.requireNonNull(handler, "handler is required");
        AIActionMetaData metadata = java.util.Objects.requireNonNull(
            required.getActionMetadata(),
            "handler action metadata is required"
        );
        this.handlerLookup = actionName -> sameAction(metadata, actionName)
            ? Optional.of(required)
            : Optional.empty();
        this.metadataLookup = actionName -> sameAction(metadata, actionName)
            ? Optional.of(metadata)
            : Optional.empty();
        this.actionCatalog = null;
    }

    @Override
    public GovernedActionInvocationOutcome invoke(GovernedActionInvocation invocation) {
        if (invocation == null) {
            return invalid("INVOCATION_REQUIRED", "Action invocation is required.");
        }

        AIActionMetaData metadata;
        try {
            metadata = requireExecutableAction(invocation);
        } catch (CapabilityDeniedException ex) {
            return denied(ex.code(), ex.getMessage());
        }

        AIActionHandler handler = handlerLookup.apply(invocation.actionName()).orElse(null);
        if (handler == null) {
            return invalid("ACTION_NOT_FOUND", "Action is not registered.");
        }
        if (metadata.getAccessMode() == null) {
            return invalid("ACTION_ACCESS_MODE_REQUIRED", "Action access mode is not configured.");
        }

        ActionContext actionContext = invocation.actionContext()
            .withActionParams(invocation.parameters());
        if (actionContext.isAnonymous()
            && !metadata.isAnonymousAllowed()) {
            return denied(
                "ACTION_NOT_ALLOWED",
                "This action requires an authenticated caller."
            );
        }

        List<String> missing = missingRequired(metadata, invocation.parameters());
        if (!missing.isEmpty()) {
            return invalid(
                "ACTION_REQUIRED_PARAMETERS_MISSING",
                "Required action parameters are missing: " + String.join(", ", missing)
            );
        }

        try {
            if (!handler.validateActionAllowed(actionContext)) {
                return denied(
                    "ACTION_NOT_ALLOWED",
                    "Action is not allowed for the current context."
                );
            }
        } catch (Exception ex) {
            return failure(
                "ACTION_AUTHORIZATION_FAILED",
                "Action authorization failed.",
                false,
                handleErrorSafely(handler, ex, actionContext)
            );
        }

        boolean confirmationRequired = handler.requiresConfirmation()
            || metadata.isConfirmationRequired();
        if (confirmationRequired
            && invocation.confirmationState() != ActionConfirmationState.CONFIRMED) {
            String message = confirmationMessage(handler, invocation.parameters(), actionContext);
            ActionResult actionResult = ActionResult.builder()
                .success(false)
                .message(message)
                .errorCode("CONFIRMATION_REQUIRED")
                .data(ActionPayload.object(Map.of(
                    "actionName", metadata.getName(),
                    "confirmationRequired", true
                )))
                .build();
            return new GovernedActionInvocationOutcome(
                GovernedActionInvocationStatus.CONFIRMATION_REQUIRED,
                actionResult,
                new ActionInvocationFailure("CONFIRMATION_REQUIRED", message, false)
            );
        }

        try {
            ActionResult result = handler.executeAction(invocation.parameters(), actionContext);
            if (result == null) {
                return failure(
                    "ACTION_EMPTY_RESULT",
                    "Action returned no result.",
                    false,
                    null
                );
            }
            if (!result.isSuccess()) {
                String reason = StringUtils.hasText(result.getErrorCode())
                    ? result.getErrorCode()
                    : "ACTION_FAILED";
                String message = StringUtils.hasText(result.getMessage())
                    ? result.getMessage()
                    : "Action failed.";
                return failure(reason, message, false, result);
            }
            return new GovernedActionInvocationOutcome(
                GovernedActionInvocationStatus.EXECUTED,
                result,
                null
            );
        } catch (Exception ex) {
            ActionResult handled = handleErrorSafely(handler, ex, actionContext);
            String reason = handled != null && StringUtils.hasText(handled.getErrorCode())
                ? handled.getErrorCode()
                : "ACTION_EXECUTION_FAILED";
            String message = handled != null && StringUtils.hasText(handled.getMessage())
                ? handled.getMessage()
                : "Action execution failed.";
            if (!metadata.getAccessMode().isReadOnly()) {
                return outcomeUnknown(reason, message, handled);
            }
            return failure(reason, message, false, handled);
        }
    }

    private AIActionMetaData requireExecutableAction(GovernedActionInvocation invocation) {
        if (actionCatalog != null) {
            return actionCatalog.requireExecutableAction(
                invocation.actionName(),
                invocation.effectiveCapabilityProfile()
            );
        }
        AIActionMetaData metadata = metadataLookup.apply(invocation.actionName())
            .orElseThrow(() -> new CapabilityDeniedException(
                "ACTION_NOT_IN_EFFECTIVE_PROFILE",
                "Action is not available in the effective capability profile"
            ));
        boolean allowed = invocation.effectiveCapabilityProfile().isActionVisible(
            invocation.actionName()
        ) && (metadata.getAccessMode() != null
            && (metadata.getAccessMode().isReadOnly()
                ? invocation.effectiveCapabilityProfile().canExecuteReadAction(
                    invocation.actionName()
                )
                : invocation.effectiveCapabilityProfile().canProposeWriteAction(
                    invocation.actionName()
                )));
        if (!allowed) {
            throw new CapabilityDeniedException(
                "ACTION_NOT_EXECUTABLE",
                "Action cannot execute in the effective capability profile"
            );
        }
        return metadata;
    }

    private static boolean sameAction(AIActionMetaData metadata, String actionName) {
        return actionName != null
            && ai.fabric.intent.action.AIActionNames.normalize(metadata.getName())
                .equals(ai.fabric.intent.action.AIActionNames.normalize(actionName));
    }

    private List<String> missingRequired(
        AIActionMetaData metadata,
        Map<String, Object> parameters
    ) {
        if (metadata.getRequiredParameters() == null
            || metadata.getRequiredParameters().isEmpty()) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String parameter : metadata.getRequiredParameters()) {
            Object value = parameters.get(parameter);
            if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
                missing.add(parameter);
            }
        }
        return List.copyOf(missing);
    }

    private String confirmationMessage(
        AIActionHandler handler,
        Map<String, Object> parameters,
        ActionContext context
    ) {
        try {
            String message = handler.getConfirmationMessage(parameters, context);
            return StringUtils.hasText(message)
                ? message
                : "Confirmation is required before executing this action.";
        } catch (Exception ignored) {
            return "Confirmation is required before executing this action.";
        }
    }

    private ActionResult handleErrorSafely(
        AIActionHandler handler,
        Exception exception,
        ActionContext context
    ) {
        try {
            return handler.handleError(exception, context);
        } catch (Exception ignored) {
            return ActionResult.builder()
                .success(false)
                .message("Action execution failed.")
                .errorCode("ACTION_EXECUTION_FAILED")
                .build();
        }
    }

    private GovernedActionInvocationOutcome invalid(String reason, String message) {
        return outcome(GovernedActionInvocationStatus.INVALID, reason, message, false, null);
    }

    private GovernedActionInvocationOutcome denied(String reason, String message) {
        return outcome(GovernedActionInvocationStatus.DENIED, reason, message, false, null);
    }

    private GovernedActionInvocationOutcome failure(
        String reason,
        String message,
        boolean retryable,
        ActionResult result
    ) {
        return outcome(GovernedActionInvocationStatus.FAILED, reason, message, retryable, result);
    }

    private GovernedActionInvocationOutcome outcomeUnknown(
        String reason,
        String message,
        ActionResult result
    ) {
        return outcome(
            GovernedActionInvocationStatus.OUTCOME_UNKNOWN,
            reason,
            message,
            false,
            result
        );
    }

    private GovernedActionInvocationOutcome outcome(
        GovernedActionInvocationStatus status,
        String reason,
        String message,
        boolean retryable,
        ActionResult result
    ) {
        ActionResult normalized = result != null
            ? result
            : ActionResult.builder()
                .success(false)
                .message(message)
                .errorCode(reason)
                .build();
        return new GovernedActionInvocationOutcome(
            status,
            normalized,
            new ActionInvocationFailure(reason, message, retryable)
        );
    }
}
