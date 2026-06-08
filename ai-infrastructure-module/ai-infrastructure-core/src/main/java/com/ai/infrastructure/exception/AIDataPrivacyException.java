package com.ai.infrastructure.exception;

/**
 * Exception thrown when AI data privacy violations are detected
 */
public class AIDataPrivacyException extends RuntimeException {
    
    private final String privacyType;
    private final String dataClassification;
    private final String userId;
    private final String requestId;
    private final String violationType;
    
    public AIDataPrivacyException(String message) {
        super(message);
        this.privacyType = "UNKNOWN";
        this.dataClassification = "UNKNOWN";
        this.userId = null;
        this.requestId = null;
        this.violationType = "UNKNOWN";
    }
    
    public AIDataPrivacyException(String message, Throwable cause) {
        super(message, cause);
        this.privacyType = "UNKNOWN";
        this.dataClassification = "UNKNOWN";
        this.userId = null;
        this.requestId = null;
        this.violationType = "UNKNOWN";
    }
    
    public AIDataPrivacyException(String message, String privacyType, String dataClassification) {
        super(message);
        this.privacyType = privacyType;
        this.dataClassification = dataClassification;
        this.userId = null;
        this.requestId = null;
        this.violationType = "UNKNOWN";
    }
    
    public AIDataPrivacyException(String message, String privacyType, String dataClassification, 
                                 String userId, String requestId, String violationType) {
        super(message);
        this.privacyType = privacyType;
        this.dataClassification = dataClassification;
        this.userId = userId;
        this.requestId = requestId;
        this.violationType = violationType;
    }
    
    public AIDataPrivacyException(String message, Throwable cause, String privacyType, 
                                 String dataClassification, String userId, String requestId, String violationType) {
        super(message, cause);
        this.privacyType = privacyType;
        this.dataClassification = dataClassification;
        this.userId = userId;
        this.requestId = requestId;
        this.violationType = violationType;
    }
    
    public String getPrivacyType() {
        return privacyType;
    }
    
    public String getDataClassification() {
        return dataClassification;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    public String getViolationType() {
        return violationType;
    }
    
    @Override
    public String toString() {
        return String.format("AIDataPrivacyException{message='%s', privacyType='%s', dataClassification='%s', userId='%s', requestId='%s', violationType='%s'}", 
                           getMessage(), privacyType, dataClassification, userId, requestId, violationType);
    }
}