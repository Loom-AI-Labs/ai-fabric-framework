package com.ai.infrastructure.intent.retrieval.connector;

/**
 * Protocol constants for the documents-only retrieval connector endpoint.
 */
public final class RetrievalConnectorProtocol {

    private RetrievalConnectorProtocol() {
    }

    public static final String PATH_DEFAULT_SEARCH = "/retrieval/search";

    public static final String KEY_QUERY = "query";
    public static final String KEY_VECTOR_SPACE = "vectorSpace";
    public static final String KEY_TOP_K = "topK";
    public static final String KEY_CURSOR = "cursor";
    public static final String KEY_FILTERS = "filters";
    public static final String KEY_TRACE = "trace";

    public static final String KEY_SUCCESS = "success";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_DOCUMENTS = "documents";
    public static final String KEY_COUNT = "count";
    public static final String KEY_TOTAL_COUNT = "totalCount";
    public static final String KEY_RESPONSE_CURSOR = "cursor";

    public static final String TRACE_REQUEST_ID = "requestId";
    public static final String TRACE_CONVERSATION_ID = "conversationId";
}
