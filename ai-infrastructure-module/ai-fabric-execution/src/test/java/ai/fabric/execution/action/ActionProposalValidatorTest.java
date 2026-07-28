package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionProposalValidatorTest {

    private final ActionProposalValidator validator =
        new ActionProposalValidator();
    private final ActionProposalSecurity security =
        new ActionProposalSecurity(
            new ObjectMapper(),
            "test-encryption-secret-with-at-least-32-characters",
            "test-fingerprint-secret-with-at-least-32-characters"
        );

    @Test
    void validatesTypedNestedParametersAndCaseInsensitiveAllowedValues() {
        AIActionMetaData metadata = metadata();

        assertThatCode(() -> validator.validateParameters(
            metadata,
            Map.of(
                "addressType",
                "billing",
                "address",
                Map.of(
                    "street",
                    "1 Main Street",
                    "postalCode",
                    "SW1A 1AA"
                )
            )
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingUnknownAndWronglyTypedParameters() {
        AIActionMetaData metadata = metadata();

        assertReason(
            () -> validator.validateParameters(metadata, Map.of()),
            "ACTION_REQUIRED_PARAMETERS_MISSING"
        );
        assertReason(
            () -> validator.validateParameters(
                metadata,
                Map.of(
                    "addressType",
                    "BILLING",
                    "address",
                    Map.of(
                        "street",
                        "1 Main Street",
                        "postalCode",
                        "SW1A 1AA"
                    ),
                    "accountId",
                    "model-supplied"
                )
            ),
            "ACTION_UNKNOWN_PARAMETERS"
        );
        assertReason(
            () -> validator.validateParameters(
                metadata,
                Map.of(
                    "addressType",
                    "OTHER",
                    "address",
                    Map.of(
                        "street",
                        "1 Main Street",
                        "postalCode",
                        "SW1A 1AA"
                    )
                )
            ),
            "ACTION_PARAMETER_INVALID"
        );
    }

    @Test
    void schemaHashChangesWhenExecutableContractChanges() {
        AIActionMetaData original = metadata();
        AIActionMetaData changed = AIActionMetaData.builder()
            .name(original.getName())
            .accessMode(original.getAccessMode())
            .confirmationRequired(true)
            .requiredParameters(original.getRequiredParameters())
            .parameterSchemas(Map.of(
                "addressType",
                AIActionParamSchema.builder()
                    .name("addressType")
                    .type(AIActionParamType.STRING)
                    .allowedValues(List.of("BILLING"))
                    .build(),
                "address",
                original.getParameterSchemas().get("address")
            ))
            .build();

        assertThat(validator.schemaHash(original, security))
            .isNotEqualTo(validator.schemaHash(changed, security));
    }

    @Test
    void rejectsUntypedAndOversizedPersistedParameters() {
        AIActionMetaData untyped = AIActionMetaData.builder()
            .name("update_address")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .parameterSchemas(Map.of(
                "payload",
                AIActionParamSchema.builder()
                    .name("payload")
                    .type(AIActionParamType.UNKNOWN)
                    .build()
            ))
            .build();
        assertReason(
            () -> validator.validateParameters(
                untyped,
                Map.of("payload", "model-data")
            ),
            "ACTION_PARAMETER_SCHEMA_INVALID"
        );

        AIActionMetaData bounded = AIActionMetaData.builder()
            .name("update_address")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .parameterSchemas(Map.of(
                "note",
                AIActionParamSchema.builder()
                    .name("note")
                    .type(AIActionParamType.STRING)
                    .build()
            ))
            .build();
        assertReason(
            () -> validator.validateParameters(
                bounded,
                Map.of("note", "x".repeat(4097))
            ),
            "ACTION_PARAMETERS_TOO_LARGE"
        );
    }

    @Test
    void rejectsNonFiniteNumericParameter() {
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("credit")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .parameterSchemas(Map.of(
                "amount",
                AIActionParamSchema.builder()
                    .name("amount")
                    .type(AIActionParamType.NUMBER)
                    .build()
            ))
            .build();

        assertReason(
            () -> validator.validateParameters(
                metadata,
                Map.of("amount", Double.NaN)
            ),
            "ACTION_PARAMETER_INVALID"
        );
    }

    @Test
    void requiresConfirmedWriteInsideEffectiveProfile() {
        AIActionMetaData metadata = metadata();
        EffectiveCapabilityProfile denied = profile(Set.of());

        assertReason(
            () -> validator.validateAction(
                metadata,
                "update_address",
                denied
            ),
            "ACTION_NOT_IN_EFFECTIVE_PROFILE"
        );

        AIActionMetaData read = AIActionMetaData.builder()
            .name("update_address")
            .accessMode(ActionAccessMode.READ)
            .confirmationRequired(true)
            .build();
        assertReason(
            () -> validator.validateAction(
                read,
                "update_address",
                profile(Set.of("update_address"))
            ),
            "SPECIALIST_WRITE_ACTION_REQUIRED"
        );
    }

    private AIActionMetaData metadata() {
        AIActionParamSchema address = AIActionParamSchema.builder()
            .name("address")
            .type(AIActionParamType.OBJECT)
            .required(true)
            .properties(Map.of(
                "street",
                AIActionParamSchema.builder()
                    .name("street")
                    .type(AIActionParamType.STRING)
                    .required(true)
                    .build(),
                "postalCode",
                AIActionParamSchema.builder()
                    .name("postalCode")
                    .type(AIActionParamType.STRING)
                    .pattern("[A-Z0-9 ]{4,10}")
                    .required(true)
                    .build()
            ))
            .requiredProperties(List.of("street", "postalCode"))
            .build();
        return AIActionMetaData.builder()
            .name("update_address")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .requiredParameters(Set.of("address"))
            .parameterSchemas(Map.of(
                "addressType",
                AIActionParamSchema.builder()
                    .name("addressType")
                    .type(AIActionParamType.STRING)
                    .allowedValues(List.of("BILLING", "SHIPPING"))
                    .build(),
                "address",
                address
            ))
            .build();
    }

    private EffectiveCapabilityProfile profile(Set<String> writes) {
        return new EffectiveCapabilityProfile(
            "DEFAULT",
            "resolver",
            false,
            Set.of(),
            writes,
            Set.of(),
            writes,
            OrchestrationPolicy.RagBudgets.defaults(),
            OrchestrationPolicy.ReadActionResolutionPolicy.defaults(),
            "profile-hash"
        );
    }

    private void assertReason(Runnable operation, String reason) {
        assertThatThrownBy(operation::run)
            .isInstanceOf(ActionProposalValidationException.class)
            .extracting(error ->
                ((ActionProposalValidationException) error).reason()
            )
            .isEqualTo(reason);
    }
}
