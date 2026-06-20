package ai.fabric.util;

import ai.fabric.exception.AIServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorRecordInputValidationTest {

    @Test
    void requireStoreInputsRejectsBlankIdentityAndMissingEmbedding() {
        assertThatThrownBy(() -> VectorRecordInputValidation.requireStoreInputs("TestProvider", " ", "id-1", List.of(0.1d)))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("TestProvider entityType must not be blank");

        assertThatThrownBy(() -> VectorRecordInputValidation.requireStoreInputs("TestProvider", "document", "", List.of(0.1d)))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("TestProvider entityId must not be blank");

        assertThatThrownBy(() -> VectorRecordInputValidation.requireStoreInputs("TestProvider", "document", "id-1", List.of()))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("TestProvider storeVector requires a non-empty embedding vector");
    }

    @Test
    void identityPredicatesTreatNullAndBlankAsAbsent() {
        assertThat(VectorRecordInputValidation.hasEntityIdentity("document", "id-1")).isTrue();
        assertThat(VectorRecordInputValidation.hasEntityIdentity(null, "id-1")).isFalse();
        assertThat(VectorRecordInputValidation.hasEntityIdentity("document", " ")).isFalse();
        assertThat(VectorRecordInputValidation.hasVectorId("vector-1")).isTrue();
        assertThat(VectorRecordInputValidation.hasVectorId(" ")).isFalse();
    }
}
