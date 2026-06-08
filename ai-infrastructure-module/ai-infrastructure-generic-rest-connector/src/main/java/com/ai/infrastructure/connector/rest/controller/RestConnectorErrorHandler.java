package com.ai.infrastructure.connector.rest.controller;

import com.ai.infrastructure.connector.rest.api.ActionResultDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class RestConnectorErrorHandler {

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ActionResultDto> handleNotFound(NoHandlerFoundException ex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("path", ex != null ? ex.getRequestURL() : null);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ActionResultDto.failure("NOT_FOUND", "Not Found", data)
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ActionResultDto handleBadRequest(IllegalArgumentException ex) {
        return ActionResultDto.failure("INVALID_REQUEST", ex != null ? ex.getMessage() : "Invalid request.");
    }

    @ExceptionHandler(IllegalStateException.class)
    public ActionResultDto handleInvalidState(IllegalStateException ex) {
        return ActionResultDto.failure("MAPPING_ERROR", ex != null ? ex.getMessage() : "Invalid configuration.");
    }

    @ExceptionHandler(Exception.class)
    public ActionResultDto handleGeneric(Exception ex) {
        log.warn("Generic REST connector failed: {}", ex != null ? ex.getMessage() : "unknown");
        return ActionResultDto.failure("SERVICE_UNAVAILABLE", "Connector service unavailable.", Map.of());
    }
}
