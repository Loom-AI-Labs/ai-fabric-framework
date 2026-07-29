package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultSpecialistAuthorityResolverTest {

    private final DefaultSpecialistAuthorityResolver resolver =
        new DefaultSpecialistAuthorityResolver();

    @Test
    void resolvesExactVersionScopeAndNormalizesCapabilities() {
        SpecialistAuthority authority = resolver.resolve(
            definition(),
            context(Set.of(
                " SPECIALIST:ACCOUNT-RESOLVER@1 ",
                "ACTION:INSPECT_ACCOUNT",
                "VECTOR:ACCOUNT-POLICY"
            ))
        );

        assertThat(authority.allowedActions()).containsExactly("inspect_account");
        assertThat(authority.allowedVectorSpaces()).containsExactly("account-policy");
    }

    @Test
    void allowsExactNameScopeAcrossVersions() {
        SpecialistAuthority authority = resolver.resolve(
            definition(),
            context(Set.of("specialist:account-resolver"))
        );

        assertThat(authority.allowedActions()).isEmpty();
        assertThat(authority.allowedVectorSpaces()).isEmpty();
    }

    @Test
    void rejectsWildcardAndPrefixScopes() {
        assertThatThrownBy(() -> resolver.resolve(
            definition(),
            context(Set.of("specialist:*", "specialist:account"))
        ))
            .isInstanceOf(
                DefaultSpecialistAuthorityResolver.AuthorityDeniedException.class
            )
            .extracting("reason")
            .isEqualTo("SPECIALIST_SCOPE_REQUIRED");
    }

    private TrustedExecutionContext context(Set<String> scopes) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal("account-service", ExecutionPrincipalType.SERVICE),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.APPLICATION,
            "tenant-1",
            "test",
            scopes,
            "correlation-1",
            Instant.parse("2026-07-28T10:00:00Z")
        );
    }

    private SpecialistDefinition<String, String> definition() {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SpecialistId.of("account-resolver", "1"),
                "Account Resolver",
                "Resolves account blockers"
            ),
            new SpecialistInstructions("Explain account blockers", null),
            new SpecialistExecutionProfile(
                "resolver",
                RequestedCapabilityProfile.retrievalOnly(Set.of("account-policy")),
                ExecutionStrategy.SINGLE_PASS,
                SpecialistWritePolicy.DISABLED
            ),
            SpecialistLimits.defaults(),
            adapter(),
            outputAdapter()
        );
    }

    private SpecialistInputAdapter<String> adapter() {
        return new SpecialistInputAdapter<>() {
            @Override
            public Class<String> inputType() {
                return String.class;
            }

            @Override
            public void validate(String input) {}

            @Override
            public String renderModelInput(String input) {
                return input;
            }
        };
    }

    private SpecialistOutputAdapter<String> outputAdapter() {
        return new SpecialistOutputAdapter<>() {
            @Override
            public Class<String> outputType() {
                return String.class;
            }

            @Override
            public String project(
                ai.fabric.intent.orchestration.OrchestrationResult result,
                List<ai.fabric.evidence.AIEvidenceReference> evidence
            ) {
                return result.getMessage();
            }

            @Override
            public void validate(String output) {}
        };
    }
}
