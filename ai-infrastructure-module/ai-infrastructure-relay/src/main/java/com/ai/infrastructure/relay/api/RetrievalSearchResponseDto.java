package com.ai.infrastructure.relay.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record RetrievalSearchResponseDto(
    boolean success,
    String message,
    String errorCode,
    List<RetrievalDocumentDto> documents,
    Integer count,
    Integer totalCount,
    String cursor
) {
    public static RetrievalSearchResponseDto ok(List<RetrievalDocumentDto> documents,
                                               Integer totalCount,
                                               String cursor) {
        int count = documents != null ? documents.size() : 0;
        return new RetrievalSearchResponseDto(true, null, null, documents, count, totalCount, cursor);
    }

    public static RetrievalSearchResponseDto failure(String errorCode, String message) {
        return new RetrievalSearchResponseDto(false, message, errorCode, List.of(), 0, null, null);
    }
}

