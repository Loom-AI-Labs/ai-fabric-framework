package com.ai.fabric.realapps.handover;

import ai.fabric.chat.control.ConversationControlViolationException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class DemoErrorHandler {

    @ExceptionHandler(ConversationControlViolationException.class)
    ResponseEntity<Map<String, Object>> control(ConversationControlViolationException error) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
            "success", false,
            "errorCode", error.code(),
            "message", error.getMessage()
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of(
            "success", false,
            "errorCode", error.getStatusCode().toString(),
            "message", error.getReason() == null ? "Request rejected." : error.getReason()
        ));
    }
}
