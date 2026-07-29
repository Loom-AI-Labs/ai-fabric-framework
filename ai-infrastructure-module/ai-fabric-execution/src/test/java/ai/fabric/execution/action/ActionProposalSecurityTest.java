package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.specialist.SpecialistId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionProposalSecurityTest {

    private final ActionProposalSecurity security =
        new ActionProposalSecurity(
            new ObjectMapper(),
            "test-encryption-secret-with-at-least-32-characters",
            "test-fingerprint-secret-with-at-least-32-characters"
        );

    @Test
    void protectsPayloadWithBindingAndDetectsTampering() {
        Map<String, Object> payload = Map.of(
            "streetAddress",
            "1 Main Street",
            "postalCode",
            "SW1A 1AA"
        );

        String protectedPayload = security.protect(
            payload,
            "receipt-1:parameters"
        );

        assertThat(protectedPayload)
            .startsWith("v1.")
            .doesNotContain("1 Main Street", "SW1A 1AA");
        assertThat(security.unprotect(
            protectedPayload,
            "receipt-1:parameters"
        )).isEqualTo(payload);
        assertThatThrownBy(() -> security.unprotect(
            protectedPayload,
            "receipt-2:parameters"
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> security.unprotect(
            protectedPayload + "x",
            "receipt-1:parameters"
        )).isInstanceOfAny(
            IllegalArgumentException.class,
            IllegalStateException.class
        );
    }

    @Test
    void canonicalHashDoesNotDependOnMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("country", "GB");
        first.put("city", "London");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("city", "London");
        second.put("country", "GB");

        assertThat(security.canonicalHash(first))
            .isEqualTo(security.canonicalHash(second));
    }

    @Test
    void protectedNumericParametersKeepTheirCanonicalHash() {
        Map<String, Object> parameters = Map.of(
            "amount",
            new BigDecimal("25.00"),
            "quantity",
            2
        );

        String protectedPayload = security.protect(
            parameters,
            "receipt-1:parameters"
        );
        Map<String, Object> restored = security.unprotect(
            protectedPayload,
            "receipt-1:parameters"
        );

        assertThat(security.canonicalHash(restored))
            .isEqualTo(security.canonicalHash(parameters));
    }

    @Test
    void identityAndIdempotencyFingerprintsDoNotExposeRawIdentifiers() {
        ActionProposalTestFixture fixture = new ActionProposalTestFixture();

        String principal = security.principalFingerprint(
            fixture.trustedContext
        );
        String subject = security.subjectFingerprint(
            fixture.trustedContext
        );
        String idempotency = security.idempotencyFingerprint(
            fixture.trustedContext,
            SpecialistId.of("account-resolver", "1"),
            "customer-request-123"
        );

        assertThat(principal)
            .hasSize(64)
            .doesNotContain("principal-1");
        assertThat(subject)
            .hasSize(64)
            .doesNotContain("account-1");
        assertThat(idempotency)
            .hasSize(64)
            .doesNotContain("customer-request-123", "account-1");
    }

    @Test
    void rejectsWeakSecrets() {
        assertThatThrownBy(() -> new ActionProposalSecurity(
            new ObjectMapper(),
            "short",
            "also-short"
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32 characters");
    }

    @Test
    void rejectsReusingOneSecretForEncryptionAndFingerprinting() {
        String shared = "shared-test-secret-with-at-least-32-characters";

        assertThatThrownBy(() -> new ActionProposalSecurity(
            new ObjectMapper(),
            shared,
            shared
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be different");
    }
}
