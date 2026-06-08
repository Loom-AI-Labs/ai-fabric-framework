package com.ai.fabric.runtime.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class RuntimeAuthContextResponse {

    @Schema(description = "Verified subject identity resolved by runtime auth.")
    private String subjectId;

    @Schema(description = "Verified subject type resolved by runtime auth.")
    private String subjectType;

    @Schema(description = "Verified auth mode resolved by runtime auth.")
    private String authMode;

    @Schema(description = "Verified caller type resolved by runtime auth.")
    private String callerType;

    @Schema(description = "Verified session identity resolved by runtime auth, when present.")
    private String sessionId;

    @Schema(description = "Deployment scope resolved by runtime auth, when present.")
    private String deploymentId;

    @Schema(description = "Customer scope resolved by runtime auth, when present.")
    private String customerId;

    @Schema(description = "Tenant scope resolved by runtime auth, when present.")
    private String tenantId;

    @Schema(description = "Verified token or proxy issuer resolved by runtime auth, when present.")
    private String issuer;

    @Schema(description = "Verified token expiry resolved by runtime auth, when present.")
    private Instant expiresAt;

    @Schema(description = "Verified token or proxy audiences resolved by runtime auth.")
    private List<String> audiences;

    @Schema(description = "Verified granted scopes resolved by runtime auth.")
    private List<String> grantedScopes;

    @Schema(description = "Conflict or policy warnings produced while resolving runtime auth context.")
    private List<String> warnings;
}
