package ai.fabric.execution.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DurableExecutionSecurityTest {

    private static final String ENCRYPTION_SECRET =
        "durable-encryption-secret-for-tests-0001";
    private static final String FINGERPRINT_SECRET =
        "durable-fingerprint-secret-for-tests-0002";

    @Test
    void protectedStateRejectsWrongBindingTamperingAndWrongKey() {
        DurableExecutionSecurity security = security(
            ENCRYPTION_SECRET,
            FINGERPRINT_SECRET
        );
        Map<String, Object> payload = Map.of(
            "subjectId",
            "account-42",
            "modelOutput",
            "Sensitive specialist result"
        );

        String protectedPayload = security.protect(
            payload,
            "exec-42:result"
        );

        assertThat(protectedPayload)
            .startsWith("v1.")
            .doesNotContain("account-42", "Sensitive specialist result");
        assertThat(security.unprotect(
            protectedPayload,
            "exec-42:result"
        )).isEqualTo(payload);
        assertThatThrownBy(() -> security.unprotect(
            protectedPayload,
            "exec-other:result"
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> security.unprotect(
            protectedPayload + "x",
            "exec-42:result"
        )).isInstanceOfAny(
            IllegalArgumentException.class,
            IllegalStateException.class
        );
        DurableExecutionSecurity wrongKey = security(
            "different-encryption-secret-for-tests-0003",
            "different-fingerprint-secret-for-tests-0004"
        );
        assertThatThrownBy(() -> wrongKey.unprotect(
            protectedPayload,
            "exec-42:result"
        )).isInstanceOf(IllegalStateException.class);
    }

    private DurableExecutionSecurity security(
        String encryptionSecret,
        String fingerprintSecret
    ) {
        return new DurableExecutionSecurity(
            new ObjectMapper(),
            encryptionSecret,
            fingerprintSecret
        );
    }
}
