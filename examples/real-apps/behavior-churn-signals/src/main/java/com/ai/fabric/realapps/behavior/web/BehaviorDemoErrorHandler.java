package com.ai.fabric.realapps.behavior.web;

import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.ai.fabric.realapps.behavior.web")
public class BehaviorDemoErrorHandler {

    @ExceptionHandler(BehaviorDemoScenarioService.BehaviorEventConflictException.class)
    ResponseEntity<Map<String, Object>> eventConflict(RuntimeException error) {
        return response(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", error.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException error) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", error.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, Object>> state(IllegalStateException error) {
        return response(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY", error.getMessage());
    }

    private ResponseEntity<Map<String, Object>> response(
        HttpStatus status,
        String code,
        String message
    ) {
        return ResponseEntity.status(status).body(Map.of(
            "timestamp", Instant.now().toString(),
            "status", status.value(),
            "code", code,
            "message", message != null ? message : status.getReasonPhrase()
        ));
    }
}
