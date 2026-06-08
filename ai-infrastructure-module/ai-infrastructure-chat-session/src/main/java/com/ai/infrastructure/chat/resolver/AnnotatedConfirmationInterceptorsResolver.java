package com.ai.infrastructure.chat.resolver;

import com.ai.infrastructure.chat.annotation.AIConfirmationInterceptors;
import com.ai.infrastructure.chat.annotation.OnPendingActionConfirmation;
import com.ai.infrastructure.chat.interception.ConfirmationInterceptionContext;
import com.ai.infrastructure.chat.interception.InterceptionDecision;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.action.PendingAction;
import com.ai.infrastructure.intent.action.PendingActionStore;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

/**
 * Framework resolver that evaluates {@link OnPendingActionConfirmation} handlers declared on
 * {@link AIConfirmationInterceptors} beans.
 *
 * <p>This keeps app-level confirmation interception declarative (annotation-driven) and avoids direct
 * {@link com.ai.infrastructure.chat.spi.IntentResolver} implementations for common patterns.</p>
 */
@Slf4j
public class AnnotatedConfirmationInterceptorsResolver extends ConfirmationResolverSupport {

    private static final int RESOLVER_PRIORITY = 8;

    private final List<InterceptorMethod> handlers;

    public AnnotatedConfirmationInterceptorsResolver(PendingActionStore pendingActionStore, ApplicationContext applicationContext) {
        super(pendingActionStore);
        this.handlers = discoverHandlers(applicationContext);
        if (!handlers.isEmpty()) {
            log.info("AnnotatedConfirmationInterceptorsResolver initialized with {} handler(s)", handlers.size());
        }
    }

    @Override
    public boolean canResolve(MultiIntentResponse intentResponse,
                              Map<String, Object> sessionMetadata,
                              PipelineContext context) {
        if (handlers.isEmpty()) {
            return false;
        }
        PendingAction pending = peekPending(context);
        if (pending == null || !StringUtils.hasText(pending.action())) {
            return false;
        }
        if (intentResponse == null || intentResponse.getIntents() == null || intentResponse.getIntents().isEmpty()) {
            return false;
        }

        Intent confirmationIntent = findFirstConfirmationIntent(intentResponse);
        if (confirmationIntent == null) {
            return false;
        }

        return findMatchingHandler(pending, confirmationIntent, intentResponse) != null;
    }

    @Override
    public PipelineContext resolve(MultiIntentResponse intentResponse,
                                   Map<String, Object> sessionMetadata,
                                   PipelineContext context) {
        PendingAction pending = peekPending(context);
        if (pending == null) {
            return context;
        }

        Intent confirmationIntent = findFirstConfirmationIntent(intentResponse);
        if (confirmationIntent == null) {
            return context;
        }

        InterceptorMethod handler = findMatchingHandler(pending, confirmationIntent, intentResponse);
        if (handler == null) {
            return context;
        }

        // Optional once/guard: set onceParam=true on the current top pending action before invoking handler.
        if (StringUtils.hasText(handler.onceParam) && handler.autoSetOnceParam) {
            trySetOnceParamOnTop(handler.onceParam, context);
        }

        ConfirmationInterceptionContext interceptionContext = new ConfirmationInterceptionContext(
            store(),
            context,
            intentResponse,
            pending,
            confirmationIntent
        );

        InterceptionDecision decision = invoke(handler, interceptionContext);
        if (decision == null || decision.intents() == null || decision.intents().isEmpty()) {
            throw new IllegalStateException("Confirmation interception handler returned empty decision: " + handler.methodId);
        }

        PipelineContext updated = context;
        if (decision.confirmedActions() != null) {
            for (String actionName : decision.confirmedActions()) {
                if (StringUtils.hasText(actionName)) {
                    updated = markConfirmed(updated, actionName.trim());
                }
            }
        }

        MultiIntentResponse updatedResponse = MultiIntentResponse.builder()
            .intents(decision.intents())
            .orchestrationStrategy(intentResponse != null ? intentResponse.getOrchestrationStrategy() : null)
            .metadata(intentResponse != null && intentResponse.getMetadata() != null ? intentResponse.getMetadata() : Map.of())
            .build();

        return updated.toBuilder()
            .intentResponse(updatedResponse)
            .build();
    }

    @Override
    public int getPriority() {
        // Run before CompoundConfirmationResolver(10) and SingleConfirmationPositiveResolver(50).
        return RESOLVER_PRIORITY;
    }

    @Override
    public String getResolverName() {
        return "AnnotatedConfirmationInterceptorsResolver";
    }

    private Intent findFirstConfirmationIntent(MultiIntentResponse intentResponse) {
        if (intentResponse == null || intentResponse.getIntents() == null) {
            return null;
        }
        for (Intent intent : intentResponse.getIntents()) {
            if (intent != null && (intent.getType() == IntentType.CONFIRMATION_POSITIVE || intent.getType() == IntentType.CONFIRMATION_NEGATIVE)) {
                return intent;
            }
        }
        return null;
    }

    private void trySetOnceParamOnTop(String key, PipelineContext context) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        PendingAction current = peekPending(context);
        if (current == null) {
            return;
        }
        Map<String, Object> params = current.actionParams();
        if (params != null && Boolean.TRUE.equals(params.get(key))) {
            return;
        }
        Map<String, Object> next = params != null ? new HashMap<>(params) : new HashMap<>();
        next.put(key, true);
        PendingAction updated = new PendingAction(
            current.action(),
            Map.copyOf(next),
            current.description(),
            current.createdAt(),
            current.trustedEvidenceValuesByKey()
        );
        popPending(context);
        pushPending(context, updated);
    }

    private InterceptionDecision invoke(InterceptorMethod handler, ConfirmationInterceptionContext ctx) {
        try {
            Object result = handler.method.invoke(handler.bean, ctx);
            if (result instanceof InterceptionDecision decision) {
                return decision;
            }
            throw new IllegalStateException("Handler did not return InterceptionDecision: " + handler.methodId);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to invoke confirmation interception handler: " + handler.methodId, ex);
        }
    }

    private InterceptorMethod findMatchingHandler(PendingAction pending, Intent confirmationIntent, MultiIntentResponse intentResponse) {
        if (pending == null || confirmationIntent == null) {
            return null;
        }
        String actionName = normalize(pending.action());
        IntentType confirmationType = confirmationIntent.getType();
        if (confirmationType == null) {
            return null;
        }
        for (InterceptorMethod handler : handlers) {
            if (handler == null) {
                continue;
            }
            if (handler.requireSingleIntent) {
                if (intentResponse == null || intentResponse.getIntents() == null || intentResponse.getIntents().size() != 1) {
                    continue;
                }
            }
            if (handler.confirmationType != confirmationType) {
                continue;
            }
            if (!handler.pendingActions.contains(actionName)) {
                continue;
            }
            if (StringUtils.hasText(handler.onceParam)) {
                Map<String, Object> params = pending.actionParams();
                if (params != null && Boolean.TRUE.equals(params.get(handler.onceParam))) {
                    continue;
                }
            }
            return handler;
        }
        return null;
    }

    private List<InterceptorMethod> discoverHandlers(ApplicationContext applicationContext) {
        if (applicationContext == null) {
            return List.of();
        }

        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(AIConfirmationInterceptors.class);
        if (beans == null || beans.isEmpty()) {
            return List.of();
        }

        List<InterceptorMethod> discovered = new ArrayList<>();
        for (Object bean : beans.values()) {
            if (bean == null) {
                continue;
            }
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            Map<Method, OnPendingActionConfirmation> methods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<OnPendingActionConfirmation>) method ->
                    AnnotatedElementUtils.findMergedAnnotation(method, OnPendingActionConfirmation.class)
            );
            if (methods == null || methods.isEmpty()) {
                continue;
            }

            for (Map.Entry<Method, OnPendingActionConfirmation> entry : methods.entrySet()) {
                Method method = entry.getKey();
                OnPendingActionConfirmation annotation = entry.getValue();
                if (annotation == null) {
                    continue;
                }
                validateSignature(targetClass, method, annotation);
                discovered.add(InterceptorMethod.from(bean, targetClass, method, annotation));
            }
        }

        if (discovered.isEmpty()) {
            return List.of();
        }

        return discovered.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator
                .comparingInt((InterceptorMethod h) -> h.priority)
                .thenComparing(h -> h.methodId))
            .toList();
    }

    private void validateSignature(Class<?> targetClass, Method method, OnPendingActionConfirmation annotation) {
        if (method == null) {
            return;
        }
        if (annotation.confirmation() != IntentType.CONFIRMATION_POSITIVE && annotation.confirmation() != IntentType.CONFIRMATION_NEGATIVE) {
            throw new IllegalStateException("OnPendingActionConfirmation.confirmation must be CONFIRMATION_POSITIVE or CONFIRMATION_NEGATIVE: "
                + targetClass.getName() + "#" + method.getName());
        }

        if (!InterceptionDecision.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException("OnPendingActionConfirmation method must return InterceptionDecision: "
                + targetClass.getName() + "#" + method.getName());
        }

        if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != ConfirmationInterceptionContext.class) {
            throw new IllegalStateException("OnPendingActionConfirmation method must accept a single ConfirmationInterceptionContext parameter: "
                + targetClass.getName() + "#" + method.getName());
        }
        if (annotation.pendingActions() == null || annotation.pendingActions().length == 0) {
            throw new IllegalStateException("OnPendingActionConfirmation.pendingActions must not be empty: "
                + targetClass.getName() + "#" + method.getName());
        }
    }

    private String normalize(String actionName) {
        return StringUtils.hasText(actionName) ? actionName.trim().toLowerCase(Locale.ROOT) : "";
    }

    private record InterceptorMethod(
        Object bean,
        Method method,
        String methodId,
        Set<String> pendingActions,
        IntentType confirmationType,
        int priority,
        boolean requireSingleIntent,
        String onceParam,
        boolean autoSetOnceParam
    ) {
        static InterceptorMethod from(Object bean, Class<?> targetClass, Method method, OnPendingActionConfirmation annotation) {
            Method invocable;
            try {
                invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to resolve invocable interceptor method: "
                    + targetClass.getName() + "#" + method.getName(), ex);
            }
            if (invocable != null) {
                try {
                    if (!invocable.canAccess(bean)) {
                        invocable.setAccessible(true);
                    }
                } catch (Exception ignored) {
                    invocable.setAccessible(true);
                }
            }
            Set<String> actions = Set.of();
            if (annotation.pendingActions() != null && annotation.pendingActions().length > 0) {
                Set<String> normalized = new java.util.LinkedHashSet<>();
                for (String a : annotation.pendingActions()) {
                    if (StringUtils.hasText(a)) {
                        normalized.add(a.trim().toLowerCase(Locale.ROOT));
                    }
                }
                actions = Set.copyOf(normalized);
            }
            String once = StringUtils.hasText(annotation.onceParam()) ? annotation.onceParam().trim() : "";
            return new InterceptorMethod(
                bean,
                invocable,
                targetClass.getName() + "#" + method.getName(),
                actions,
                annotation.confirmation(),
                annotation.priority(),
                annotation.requireSingleIntent(),
                once,
                annotation.autoSetOnceParam()
            );
        }
    }
}
