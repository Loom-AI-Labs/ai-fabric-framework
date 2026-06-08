package com.ai.infrastructure.exception;

/**
 * Base exception for AI service operations
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
public class AIServiceException extends RuntimeException {
    
    public AIServiceException(String message) {
        super(message);
    }
    
    public AIServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
