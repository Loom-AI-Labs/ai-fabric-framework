package com.ai.fabric.realapps.incident.web;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IncidentErrorHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class
    })
    ResponseEntity<Map<String, Object>> badRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> conflict(IllegalStateException exception) {
        return response(HttpStatus.CONFLICT, exception.getMessage());
    }

    private ResponseEntity<Map<String, Object>> response(
        HttpStatus status,
        String message
    ) {
        return ResponseEntity.status(status).body(Map.of(
            "status",
            status.value(),
            "error",
            status.getReasonPhrase(),
            "message",
            message == null ? "Request failed" : message,
            "timestamp",
            Instant.now().toString()
        ));
    }
}
