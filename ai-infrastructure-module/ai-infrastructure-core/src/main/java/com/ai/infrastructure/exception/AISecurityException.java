package com.ai.infrastructure.exception;

/**
 * Exception thrown when AI security violations are detected
 */
public class AISecurityException extends RuntimeException {
    
    private final String securityLevel;
    private final String threatType;
    private final String userId;
    private final String requestId;
    
    public AISecurityException(String message) {
        super(message);
        this.securityLevel = "UNKNOWN";
        this.threatType = "UNKNOWN";
        this.userId = null;
        this.requestId = null;
    }
    
    public AISecurityException(String message, Throwable cause) {
        super(message, cause);
        this.securityLevel = "UNKNOWN";
        this.threatType = "UNKNOWN";
        this.userId = null;
        this.requestId = null;
    }
    
    public AISecurityException(String message, String securityLevel, String threatType) {
        super(message);
        this.securityLevel = securityLevel;
        this.threatType = threatType;
        this.userId = null;
        this.requestId = null;
    }
    
    public AISecurityException(String message, String securityLevel, String threatType, 
                              String userId, String requestId) {
        super(message);
        this.securityLevel = securityLevel;
        this.threatType = threatType;
        this.userId = userId;
        this.requestId = requestId;
    }
    
    public AISecurityException(String message, Throwable cause, String securityLevel, 
                              String threatType, String userId, String requestId) {
        super(message, cause);
        this.securityLevel = securityLevel;
        this.threatType = threatType;
        this.userId = userId;
        this.requestId = requestId;
    }
    
    public String getSecurityLevel() {
        return securityLevel;
    }
    
    public String getThreatType() {
        return threatType;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    @Override
    public String toString() {
        return String.format("AISecurityException{message='%s', securityLevel='%s', threatType='%s', userId='%s', requestId='%s'}", 
                           getMessage(), securityLevel, threatType, userId, requestId);
    }
}