package com.ai.fabric.realapps.agenticresolver.controller;

import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverSessionService;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
    AgenticResolverController.class,
    AgenticResolverManagerController.class,
    ProactiveAccountEventController.class,
    SupportCreditReviewController.class,
    DemoReviewController.class
})
public class AgenticResolverErrorHandler {

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException ex) {
        return error(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(
        AgenticResolverSessionService.SessionCapacityExceededException.class
    )
    ResponseEntity<Map<String, String>> capacity(RuntimeException ex) {
        return error(
            HttpStatus.TOO_MANY_REQUESTS,
            "SESSION_CAPACITY_EXCEEDED",
            ex.getMessage()
        );
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    ResponseEntity<Map<String, String>> invalid(Exception ex) {
        return error(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "The agentic resolver request is invalid."
        );
    }

    private ResponseEntity<Map<String, String>> error(
        HttpStatus status,
        String code,
        String message
    ) {
        return ResponseEntity.status(status).body(Map.of(
            "code",
            code,
            "message",
            message != null ? message : status.getReasonPhrase()
        ));
    }
}
