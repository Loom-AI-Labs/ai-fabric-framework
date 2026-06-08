package com.ai.infrastructure.chat.exception;

public class ChatSessionNotFoundException extends RuntimeException {
    public ChatSessionNotFoundException(String message) {
        super(message);
    }
}

