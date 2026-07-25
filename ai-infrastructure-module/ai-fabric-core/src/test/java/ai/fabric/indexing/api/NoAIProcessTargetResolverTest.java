package ai.fabric.indexing.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NoAIProcessTargetResolverTest {

    @Test
    void reportsAccidentalInvocationAsAContractViolation() {
        NoAIProcessTargetResolver resolver = new NoAIProcessTargetResolver();

        assertThatThrownBy(() -> resolver.resolve(null))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessage("No custom AI process target resolver is configured");
    }
}
