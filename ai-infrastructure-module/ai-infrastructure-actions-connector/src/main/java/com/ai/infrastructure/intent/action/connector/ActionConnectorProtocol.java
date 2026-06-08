package com.ai.infrastructure.intent.action.connector;

/**
 * Wire protocol constants for the Customer Connector API.
 */
public final class ActionConnectorProtocol {

    private ActionConnectorProtocol() {
    }

    public static final String PATH_DEFAULT_EXECUTE = "/actions/execute";

    public static final String KEY_ACTION_ID = "actionId";
    public static final String KEY_PARAMS = "params";
    public static final String KEY_IDEMPOTENCY_KEY = "idempotencyKey";
    public static final String KEY_TRACE = "trace";

    public static final String KEY_SUCCESS = "success";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_DATA = "data";
    public static final String KEY_PINNED_TARGETS = "pinnedTargets";
    public static final String KEY_ERROR_CODE = "errorCode";

    public static final String TRACE_REQUEST_ID = "requestId";
    public static final String TRACE_CONVERSATION_ID = "conversationId";
    public static final String TRACE_USER_ID = "userId";
    public static final String TRACE_SESSION_ID = "sessionId";
    public static final String TRACE_AUTH_CONTEXT = "authContext";
}
