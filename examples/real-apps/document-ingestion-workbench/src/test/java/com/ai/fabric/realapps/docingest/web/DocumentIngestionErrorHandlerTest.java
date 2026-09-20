package com.ai.fabric.realapps.docingest.web;

import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIngestionErrorHandlerTest {

    private final DocumentIngestionErrorHandler handler =
        new DocumentIngestionErrorHandler();

    @Test
    void returnsStableFailureCodeForOversizedMultipartRequest() {
        var response = handler.uploadTooLarge(
            new MaxUploadSizeExceededException(1_000_000L)
        );

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
            .isEqualTo("DOCUMENT_LIMIT_EXCEEDED");
        assertThat(response.getBody().message())
            .doesNotContain("exception", "stack");
    }

    @Test
    void preservesTypedFrameworkFailureWithoutExposingItsCause() {
        var response = handler.documentFailure(new DocumentIngestionException(
            DocumentIngestionFailureCode.DOCUMENT_PARSE_FAILED,
            "Document transformation failed",
            new IllegalStateException("private parser path")
        ));

        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
            .isEqualTo("DOCUMENT_PARSE_FAILED");
        assertThat(response.getBody().message())
            .isEqualTo("Document transformation failed");
    }
}
