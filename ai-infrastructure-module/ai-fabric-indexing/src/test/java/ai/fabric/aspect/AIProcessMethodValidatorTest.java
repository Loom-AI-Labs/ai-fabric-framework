package ai.fabric.aspect;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.AIProcessContractException;
import ai.fabric.indexing.api.AIProcessOperation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIProcessMethodValidatorTest {

    private final AIProcessMethodValidator validator =
        new AIProcessMethodValidator();

    @Test
    void acceptsPublicNonTransactionalBoundary() {
        ValidBoundary bean = new ValidBoundary();

        assertThat(validator.postProcessAfterInitialization(bean, "valid"))
            .isSameAs(bean);
    }

    @Test
    void rejectsPrivateBoundaryThatSpringCannotIntercept() {
        assertThatThrownBy(() -> validator.postProcessAfterInitialization(
            new PrivateBoundary(),
            "private"
        ))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessageContaining("public, non-static, and non-final");
    }

    @Test
    void rejectsFinalMethodAndFinalClass() {
        assertThatThrownBy(() -> validator.postProcessAfterInitialization(
            new FinalMethodBoundary(),
            "finalMethod"
        ))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessageContaining("public, non-static, and non-final");

        AIProcessMethodValidator secondValidator =
            new AIProcessMethodValidator();
        assertThatThrownBy(() -> secondValidator.postProcessAfterInitialization(
            new FinalClassBoundary(),
            "finalClass"
        ))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessageContaining("declaring class must be proxyable");
    }

    @Test
    void rejectsCallsFromTheSameBeanThatWouldBypassTheProxy() {
        assertThatThrownBy(() -> validator.postProcessAfterInitialization(
            new SelfInvokingBoundary(),
            "selfInvoking"
        ))
            .isInstanceOf(AIProcessContractException.class)
            .hasMessageContaining("would bypass proxy interception")
            .hasMessageContaining("apply");
    }

    static class ValidBoundary {
        @AIProcess(operation = AIProcessOperation.UPDATE)
        public Object apply() {
            return new Object();
        }
    }

    static class PrivateBoundary {
        @AIProcess(operation = AIProcessOperation.UPDATE)
        private Object apply() {
            return new Object();
        }
    }

    static class FinalMethodBoundary {
        @AIProcess(operation = AIProcessOperation.UPDATE)
        public final Object apply() {
            return new Object();
        }
    }

    static final class FinalClassBoundary {
        @AIProcess(operation = AIProcessOperation.UPDATE)
        public Object apply() {
            return new Object();
        }
    }

    static class SelfInvokingBoundary {
        public Object save() {
            return apply();
        }

        @AIProcess(operation = AIProcessOperation.UPDATE)
        public Object apply() {
            return new Object();
        }
    }
}
