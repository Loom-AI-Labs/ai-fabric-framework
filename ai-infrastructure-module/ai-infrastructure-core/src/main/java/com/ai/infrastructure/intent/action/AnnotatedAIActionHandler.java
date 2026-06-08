package com.ai.infrastructure.intent.action;

import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionFacts;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
final class AnnotatedAIActionHandler implements AIActionHandler {

    private final Object target;
    private final AIAction annotation;
    private final AIActionMetaData metadata;
    private final Method executeMethod;
    private final Method allowedMethod;
    private final Method confirmationMethod;
    private final Method factsMethod;
    private final ActionMethodArgumentBinder binder;

    AnnotatedAIActionHandler(Object target,
                             AIAction annotation,
                             AIActionMetaData metadata,
                             Method executeMethod,
                             Method allowedMethod,
                             Method confirmationMethod,
                             Method factsMethod,
                             ActionMethodArgumentBinder binder) {
        this.target = target;
        this.annotation = annotation;
        this.metadata = metadata;
        this.executeMethod = makeAccessible(executeMethod);
        this.allowedMethod = makeAccessible(allowedMethod);
        this.confirmationMethod = makeAccessible(confirmationMethod);
        this.factsMethod = makeAccessible(factsMethod);
        this.binder = binder;
    }

    @Override
    public AIActionMetaData getActionMetadata() {
        return metadata;
    }

    @Override
    public boolean requiresConfirmation() {
        return annotation.requiresConfirmation();
    }

    @Override
    public boolean validateActionAllowed(ActionContext context) {
        if (allowedMethod == null) {
            return true;
        }
        try {
            Object[] args = binder.bind(allowedMethod, Map.of(), context);
            Object result = allowedMethod.invoke(target, args);
            return result instanceof Boolean allowed && allowed;
        } catch (Exception ex) {
            log.debug("Failed to evaluate @ActionAllowed for {}: {}", metadata != null ? metadata.getName() : "unknown", ex.getMessage());
            return false;
        }
    }

    @Override
    public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
        if (confirmationMethod == null) {
            return defaultConfirmationMessage(params);
        }
        try {
            Object[] args = binder.bind(confirmationMethod, params != null ? params : Map.of(), context);
            Object result = confirmationMethod.invoke(target, args);
            return result != null ? result.toString() : null;
        } catch (Exception ex) {
            log.debug("Failed to build confirmation message for action {}: {}", metadata != null ? metadata.getName() : "unknown", ex.getMessage());
            return defaultConfirmationMessage(params);
        }
    }

    @Override
    public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
        try {
            Object[] args = binder.bind(executeMethod, params != null ? params : Map.of(), context);
            Object result = executeMethod.invoke(target, args);
            if (result == null) {
                return ActionResult.builder()
                    .success(true)
                    .message("Done.")
                    .build();
            }
            if (result instanceof ActionResult actionResult) {
                return actionResult;
            }
            if (result instanceof ActionPayload payload) {
                return ActionResult.builder()
                    .success(true)
                    .message("Done.")
                    .data(payload)
                    .build();
            }
            throw new IllegalStateException("AIAction @ActionExecute method must return ActionResult or ActionPayload: "
                + executeMethod.getDeclaringClass().getName() + "#" + executeMethod.getName());
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException() != null ? ex.getTargetException() : ex;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (ActionMethodArgumentBinder.MissingActionParameterException | ActionMethodArgumentBinder.InvalidActionParameterException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Optional<Map<String, Object>> buildPostActionLlmFacts(ActionResult actionResult, ActionContext context) {
        if (factsMethod == null) {
            return Optional.empty();
        }
        if (!factsMethod.isAnnotationPresent(ActionFacts.class)) {
            return Optional.empty();
        }

        try {
            Object result = factsMethod.getParameterCount() == 0
                ? factsMethod.invoke(target)
                : factsMethod.invoke(target, actionResult, context);

            if (result == null) {
                return Optional.empty();
            }
            if (result instanceof Optional<?> optional) {
                @SuppressWarnings("unchecked")
                Optional<Map<String, Object>> typed = (Optional<Map<String, Object>>) optional;
                return typed;
            }
            if (result instanceof Map<?, ?> map) {
                Map<String, Object> casted = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) {
                        casted.put(entry.getKey().toString(), entry.getValue());
                    }
                }
                return Optional.of(Map.copyOf(casted));
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.debug("Failed to build post-action facts for {}: {}", metadata != null ? metadata.getName() : "unknown", ex.getMessage());
            return Optional.empty();
        }
    }

    private Method makeAccessible(Method method) {
        if (method == null) {
            return null;
        }
        if (!method.canAccess(target)) {
            method.setAccessible(true);
        }
        return method;
    }

    private String defaultConfirmationMessage(Map<String, Object> params) {
        String actionName = metadata != null ? metadata.getName() : "action";
        if (params == null || params.isEmpty()) {
            return "Confirm " + actionName + "?";
        }
        Map<String, Object> safe = new LinkedHashMap<>(params);
        String joined = safe.entrySet().stream()
            .filter(e -> e.getKey() != null && StringUtils.hasText(e.getKey()))
            .limit(6)
            .map(e -> e.getKey() + "=" + (e.getValue() == null ? "null" : String.valueOf(e.getValue())))
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
        return "Confirm " + actionName + " (" + joined + ")?";
    }
}
