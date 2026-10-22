package ai.fabric.execution.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrustedExecutionContextTest {

    @Test
    void callerIdentityIsImmutableAndCannotUpgradeAnonymousOrChangeItsSession() {
        var audiences = new java.util.ArrayList<>(java.util.List.of(" runtime ", "runtime"));
        var identity = new ExecutionCallerIdentity("ANONYMOUS_SESSION", "PUBLIC_RUNTIME_TOKEN", "PUBLIC_BROWSER",
            "anonymous-1", "customer-1", "issuer", audiences, null);
        audiences.clear();
        assertThat(identity.audiences()).containsExactly("runtime");
        assertThatThrownBy(() -> identity.audiences().add("admin")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ExecutionCallerIdentity.restore(identity.snapshot())).isEqualTo(identity);
        assertThat(ExecutionCallerIdentity.restore(null)).isNull();
        assertThatThrownBy(() -> ExecutionCallerIdentity.restore(java.util.Map.of("sessionId", 12)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionCallerIdentity.restore(java.util.Map.of("audiences", java.util.List.of(12))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionCallerIdentity.restore(java.util.Map.of("token", "must-not-persist")))
            .isInstanceOf(IllegalArgumentException.class);
        var context = new TrustedExecutionContext(new ExecutionPrincipal("anonymous-1", ExecutionPrincipalType.ANONYMOUS_SESSION),
            new ExecutionSubjectRef("conversation", "conversation-1"), ExecutionSource.INTERACTIVE,
            "tenant-1", "deployment-1", Set.of("chat:query"), "request-1", null);
        assertThat(context.withCallerIdentity(identity).isAuthenticated()).isFalse();
        assertThatThrownBy(() -> context.withCallerIdentity(new ExecutionCallerIdentity("END_USER", null, null,
            "anonymous-1", null, null, null, null))).hasMessageContaining("authentication class");
        assertThatThrownBy(() -> context.withCallerIdentity(new ExecutionCallerIdentity("ANONYMOUS_SESSION", null, null,
            "foreign-session", null, null, null, null))).hasMessageContaining("verified initiator");
        var user = new TrustedExecutionContext(new ExecutionPrincipal("user-1", ExecutionPrincipalType.END_USER),
            context.subject(), ExecutionSource.INTERACTIVE, "tenant-1", "deployment-1", Set.of(), "request-1", null);
        assertThatThrownBy(() -> user.withCallerIdentity(identity)).hasMessageContaining("authentication class");
    }

    @Test
    void applicationContextRequiresServiceOrSystemInitiator() {
        assertThatThrownBy(() -> new TrustedExecutionContext(
            new ExecutionPrincipal("user-1", ExecutionPrincipalType.END_USER),
            new ExecutionSubjectRef("ACCOUNT", "account-1"),
            ExecutionSource.APPLICATION,
            "tenant-1",
            null,
            Set.of(),
            "correlation-1",
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SERVICE or SYSTEM");
    }

    @Test
    void interactiveContextRequiresEndUserInitiator() {
        assertThatThrownBy(() -> new TrustedExecutionContext(
            new ExecutionPrincipal("service-1", ExecutionPrincipalType.SERVICE),
            null,
            ExecutionSource.INTERACTIVE,
            null,
            null,
            Set.of(),
            "correlation-1",
            null
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("END_USER");
    }

    @Test
    void normalizesAndDefensivelyCopiesScopes() {
        LinkedHashSet<String> scopes = new LinkedHashSet<>(Set.of(" account:read ", "policy:read"));

        TrustedExecutionContext context = TrustedExecutionContext.application(
            " resolver-service ",
            new ExecutionSubjectRef("ACCOUNT", "account-1"),
            " tenant-1 ",
            scopes
        );
        scopes.clear();

        assertThat(context.initiator().principalId()).isEqualTo("resolver-service");
        assertThat(context.tenantId()).isEqualTo("tenant-1");
        assertThat(context.grantedScopes()).containsExactlyInAnyOrder("account:read", "policy:read");
        assertThat(context.correlationId()).startsWith("exec-");
        assertThatThrownBy(() -> context.grantedScopes().add("other"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
