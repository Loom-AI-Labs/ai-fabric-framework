package ai.fabric.execution.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TrustedExecutionContextTest {

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
