package com.ai.fabric.realapps.docingest.web;

import ai.fabric.indexing.document.model.DocumentIngestionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class DocumentIngestionErrorHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> uploadTooLarge(
        MaxUploadSizeExceededException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(
            new ErrorResponse(
                "DOCUMENT_LIMIT_EXCEEDED",
                "Document upload exceeds the configured request limit"
            )
        );
    }

    @ExceptionHandler(DocumentIngestionException.class)
    ResponseEntity<ErrorResponse> documentFailure(
        DocumentIngestionException exception
    ) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(
            new ErrorResponse(exception.getCode().name(), exception.getMessage())
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidRequest(
        IllegalArgumentException exception
    ) {
        return ResponseEntity.badRequest().body(
            new ErrorResponse("INVALID_DOCUMENT_REQUEST", exception.getMessage())
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> lifecycleConflict(
        IllegalStateException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            new ErrorResponse("DOCUMENT_LIFECYCLE_CONFLICT", exception.getMessage())
        );
    }

    record ErrorResponse(String code, String message) { }
}
