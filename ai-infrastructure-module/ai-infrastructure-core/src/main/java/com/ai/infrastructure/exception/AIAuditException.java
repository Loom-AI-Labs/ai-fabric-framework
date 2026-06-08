package com.ai.infrastructure.exception;

/**
 * Exception thrown when AI audit violations are detected
 */
public class AIAuditException extends RuntimeException {
    
    private final String auditType;
    private final String riskLevel;
    private final String userId;
    private final String requestId;
    private final String anomalyType;
    
    public AIAuditException(String message) {
        super(message);
        this.auditType = "UNKNOWN";
        this.riskLevel = "UNKNOWN";
        this.userId = null;
        this.requestId = null;
        this.anomalyType = "UNKNOWN";
    }
    
    public AIAuditException(String message, Throwable cause) {
        super(message, cause);
        this.auditType = "UNKNOWN";
        this.riskLevel = "UNKNOWN";
        this.userId = null;
        this.requestId = null;
        this.anomalyType = "UNKNOWN";
    }
    
    public AIAuditException(String message, String auditType, String riskLevel) {
        super(message);
        this.auditType = auditType;
        this.riskLevel = riskLevel;
        this.userId = null;
        this.requestId = null;
        this.anomalyType = "UNKNOWN";
    }
    
    public AIAuditException(String message, String auditType, String riskLevel, 
                           String userId, String requestId, String anomalyType) {
        super(message);
        this.auditType = auditType;
        this.riskLevel = riskLevel;
        this.userId = userId;
        this.requestId = requestId;
        this.anomalyType = anomalyType;
    }
    
    public AIAuditException(String message, Throwable cause, String auditType, 
                           String riskLevel, String userId, String requestId, String anomalyType) {
        super(message, cause);
        this.auditType = auditType;
        this.riskLevel = riskLevel;
        this.userId = userId;
        this.requestId = requestId;
        this.anomalyType = anomalyType;
    }
    
    public String getAuditType() {
        return auditType;
    }
    
    public String getRiskLevel() {
        return riskLevel;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public String getRequestId() {
        return requestId;
    }
    
    public String getAnomalyType() {
        return anomalyType;
    }
    
    @Override
    public String toString() {
        return String.format("AIAuditException{message='%s', auditType='%s', riskLevel='%s', userId='%s', requestId='%s', anomalyType='%s'}", 
                           getMessage(), auditType, riskLevel, userId, requestId, anomalyType);
    }
}