package com.ai.infrastructure.exception;

/**
 * Exception thrown when AI compliance violations are detected
 */
public class AIComplianceException extends RuntimeException {
    
    private final String complianceType;
    private final String regulationType;
    private final String userId;
    private final String requestId;
    private final String violationType;
    
    public AIComplianceException(String message) {
        super(message);
        this.complianceType = "UNKNOWN";
        this.regulationType = "UNKNOWN";
        this.userId = null;
        this.requestId = null;
        this.violationType = "UNKNOWN";
    }
    
    public AIComplianceException(String message, Throwable cause) {
        super(message, cause);
        this.complianceType = "UNKNOWN";
        this.regulationType = "UNKNOWN";
        this.userId = null;
        this.requestId = null;
        this.violationType = "UNKNOWN";
    }
    
    public AIComplianceException(String message, String complianceType, String regulationType) {
        super(message);
        this.complianceType = complianceType;
        this.regulationType = regulationType;
        this.userId = null;
        this.requestId = null;
        this.violationType = "UNKNOWN";
    }
    
    public AIComplianceException(String message, String complianceType, String regulationType, 
                                String userId, String requestId, String violationType) {
        super(message);
        this.complianceType = complianceType;
        this.regulationType = regulationType;
        this.userId = userId;
        this.requestId = requestId;
        this.violationType = violationType;
    }
    
    public AIComplianceException(String message, Throwable cause, String complianceType, 
                                String regulationType, String userId, String requestId, String violationType) {
        super(message, cause);
        this.complianceType = complianceType;
        this.regulationType = regulationType;
        this.userId = userId;
        this.requestId = requestId;
        this.violationType = violationType;
    }
    
    public String getComplianceType() {
        return complianceType;
    }
    
    public String getRegulationType() {
        return regulationType;
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
        return String.format("AIComplianceException{message='%s', complianceType='%s', regulationType='%s', userId='%s', requestId='%s', violationType='%s'}", 
                           getMessage(), complianceType, regulationType, userId, requestId, violationType);
    }
}