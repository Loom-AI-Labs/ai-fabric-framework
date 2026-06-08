package com.ai.fabric.runtime.chat;

import org.hibernate.Hibernate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class RuntimeConversationGateway {

    private final ObjectProvider<Object> chatSessionServiceProvider;

    public RuntimeConversationGateway(@Qualifier("chatSessionService") ObjectProvider<Object> chatSessionServiceProvider) {
        this.chatSessionServiceProvider = chatSessionServiceProvider;
    }

    public boolean isAvailable() {
        return chatSessionServiceProvider.getIfAvailable() != null;
    }

    public RuntimeConversationRecord getConversation(String conversationId, String ownerId) {
        Object service = chatSessionServiceProvider.getIfAvailable();
        if (service == null) {
            return null;
        }
        return mapSession(invoke(service, "getSession", conversationId, ownerId), true);
    }

    public List<RuntimeConversationRecord> listConversations(String ownerId) {
        Object service = chatSessionServiceProvider.getIfAvailable();
        if (service == null) {
            return List.of();
        }
        Object rawResult = invoke(service, "getUserConversations", ownerId);
        if (!(rawResult instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<RuntimeConversationRecord> conversations = new ArrayList<>();
        for (Object rawSession : iterable) {
            RuntimeConversationRecord mapped = mapSession(rawSession, false);
            if (mapped != null) {
                conversations.add(mapped);
            }
        }
        return List.copyOf(conversations);
    }

    public void deleteConversation(String conversationId, String ownerId) {
        Object service = chatSessionServiceProvider.getIfAvailable();
        if (service == null) {
            return;
        }
        invoke(service, "deleteConversation", conversationId, ownerId);
    }

    private RuntimeConversationRecord mapSession(Object rawSession, boolean includeTurns) {
        if (rawSession == null) {
            return null;
        }
        return new RuntimeConversationRecord(
            asString(invoke(rawSession, "getId")),
            asString(invoke(rawSession, "getOwnerId")),
            asString(invoke(rawSession, "getStatus")),
            asLocalDateTime(invoke(rawSession, "getCreatedAt")),
            asLocalDateTime(invoke(rawSession, "getLastInteractionAt")),
            includeTurns ? mapTurns(invoke(rawSession, "getTurns")) : List.of()
        );
    }

    private List<RuntimeConversationTurnRecord> mapTurns(Object rawTurns) {
        if (!(rawTurns instanceof Iterable<?> iterable) || !Hibernate.isInitialized(rawTurns)) {
            return List.of();
        }
        List<RuntimeConversationTurnRecord> turns = new ArrayList<>();
        for (Object rawTurn : iterable) {
            if (rawTurn == null) {
                continue;
            }
            turns.add(new RuntimeConversationTurnRecord(
                asLocalDateTime(invoke(rawTurn, "getTimestamp")),
                asString(invoke(rawTurn, "getUserQuery")),
                asString(invoke(rawTurn, "getAiResponse"))
            ));
        }
        return List.copyOf(turns);
    }

    private Object invoke(Object target, String methodName, Object... args) {
        Method method = resolveMethod(target.getClass(), methodName, args);
        if (method == null) {
            throw new IllegalStateException("Runtime conversation gateway could not resolve method '%s' on %s"
                .formatted(methodName, target.getClass().getName()));
        }
        ReflectionUtils.makeAccessible(method);
        try {
            return ReflectionUtils.invokeMethod(method, target, args);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Runtime conversation gateway failed invoking '%s' on %s"
                .formatted(methodName, target.getClass().getName()), ex);
        }
    }

    private Method resolveMethod(Class<?> targetType, String methodName, Object... args) {
        if (args.length == 0) {
            return ReflectionUtils.findMethod(targetType, methodName);
        }
        if (args.length == 1 && args[0] instanceof String) {
            return ReflectionUtils.findMethod(targetType, methodName, String.class);
        }
        if (args.length == 2 && args[0] instanceof String && args[1] instanceof String) {
            return ReflectionUtils.findMethod(targetType, methodName, String.class, String.class);
        }
        Class<?>[] parameterTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            parameterTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
        }
        return ReflectionUtils.findMethod(targetType, methodName, parameterTypes);
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private LocalDateTime asLocalDateTime(Object value) {
        return value instanceof LocalDateTime localDateTime ? localDateTime : null;
    }
}
