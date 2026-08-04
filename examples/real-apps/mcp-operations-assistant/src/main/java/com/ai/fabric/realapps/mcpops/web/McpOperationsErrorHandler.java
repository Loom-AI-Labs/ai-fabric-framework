package com.ai.fabric.realapps.mcpops.web;

import com.ai.fabric.realapps.mcpops.service.McpOperationsService;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class McpOperationsErrorHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
            "INVALID_REQUEST",
            exception.getMessage(),
            Instant.now()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(
        MethodArgumentNotValidException exception
    ) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
            "INVALID_REQUEST",
            "The request is invalid.",
            Instant.now()
        ));
    }

    @ExceptionHandler(McpOperationsService.McpOperationsUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable(
        McpOperationsService.McpOperationsUnavailableException exception
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorResponse(
                exception.code(),
                exception.getMessage(),
                Instant.now()
            ));
    }

    public record ErrorResponse(
        String code,
        String message,
        Instant timestamp
    ) {
    }
}
