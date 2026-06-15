package ai.fabric.datasync.dto;

import java.util.Map;

/**
 * Trace context for data sync requests.
 *
 * <p>Used for access control, correlation, and audit logging. Values should be stable identifiers
 * (avoid PII such as email/phone). {@link #authContext} is the only authoritative caller identity
 * contract for secure data-sync operations.</p>
 */
public class DataSyncTrace {

    /**
     * Optional request identifier. If absent, the server may generate one for logging.
     */
    private String requestId;

    /**
     * Optional trace metadata (PII-safe).
     */
    private Map<String, Object> metadata;

    /**
     * Optional canonical verified auth context for the caller.
     */
    private DataSyncVerifiedAuthContext authContext;

    public DataSyncTrace() {
    }

    public DataSyncTrace(String requestId, Map<String, Object> metadata) {
        this.requestId = requestId;
        this.metadata = metadata;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public DataSyncVerifiedAuthContext getAuthContext() {
        return authContext;
    }

    public void setAuthContext(DataSyncVerifiedAuthContext authContext) {
        this.authContext = authContext;
    }
}
