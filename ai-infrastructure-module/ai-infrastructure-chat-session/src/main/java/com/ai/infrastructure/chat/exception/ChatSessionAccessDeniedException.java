package com.ai.infrastructure.chat.exception;

public class ChatSessionAccessDeniedException extends RuntimeException {
    public ChatSessionAccessDeniedException(String message) {
        super(message);
    }
}

