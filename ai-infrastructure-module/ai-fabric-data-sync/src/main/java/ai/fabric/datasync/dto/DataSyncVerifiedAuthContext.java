package ai.fabric.datasync.dto;

import java.util.List;

/**
 * Canonical verified caller context for data-sync operations.
 *
 * <p>Secure callers must populate this structure so downstream authorization and
 * auditing can reason about the real verified actor and scope.</p>
 */
public class DataSyncVerifiedAuthContext {

    private String subjectId;
    private String subjectType;
    private String authMode;
    private String callerType;
    private String sessionId;
    private String deploymentId;
    private String customerId;
    private String tenantId;
    private String issuer;
    private List<String> grantedScopes;

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public void setSubjectType(String subjectType) {
        this.subjectType = subjectType;
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        this.authMode = authMode;
    }

    public String getCallerType() {
        return callerType;
    }

    public void setCallerType(String callerType) {
        this.callerType = callerType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public void setDeploymentId(String deploymentId) {
        this.deploymentId = deploymentId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public List<String> getGrantedScopes() {
        return grantedScopes;
    }

    public void setGrantedScopes(List<String> grantedScopes) {
        this.grantedScopes = grantedScopes;
    }
}
