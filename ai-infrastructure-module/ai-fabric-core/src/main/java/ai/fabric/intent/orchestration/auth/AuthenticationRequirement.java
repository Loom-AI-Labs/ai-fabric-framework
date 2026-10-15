package ai.fabric.intent.orchestration.auth;

/**
 * Deterministic client contract emitted when an anonymous caller selects a
 * capability that requires a verified end-user identity.
 *
 * <p>The host binds this requirement to its configured identity provider. The
 * framework never supplies a login URL and never replays the blocked action.</p>
 */
public record AuthenticationRequirement(
    String schemaVersion,
    String reason,
    String requiredSubjectType,
    String action
) {
    public static final String ERROR_CODE = "AUTHENTICATION_REQUIRED";
    public static final String DATA_KEY = "authenticationRequirement";
    public static final String SCHEMA_VERSION = "ai-fabric-authentication-requirement-v1";
    public static final String REASON_ACTION_REQUIRES_AUTHENTICATED_END_USER =
        "ACTION_REQUIRES_AUTHENTICATED_END_USER";
    public static final String SUBJECT_TYPE_END_USER = "END_USER";

    public static AuthenticationRequirement forAction(String action) {
        return new AuthenticationRequirement(
            SCHEMA_VERSION,
            REASON_ACTION_REQUIRES_AUTHENTICATED_END_USER,
            SUBJECT_TYPE_END_USER,
            action
        );
    }
}
