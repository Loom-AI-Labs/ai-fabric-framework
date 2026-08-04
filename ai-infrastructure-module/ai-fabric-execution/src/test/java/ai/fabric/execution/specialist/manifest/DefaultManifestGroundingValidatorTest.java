package ai.fabric.execution.specialist.manifest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultManifestGroundingValidatorTest {

    private final DefaultManifestGroundingValidator validator =
        new DefaultManifestGroundingValidator();

    @Test
    void acceptsAnyGroundingUsableRequestableReadAction() {
        SpecialistGroundingValidationContext context = context(
            "list_recent_incidents",
            true
        );

        assertThatCode(() -> validator.validate(context))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsReadActionOutsideTheSpecialistCapabilitySet() {
        SpecialistGroundingValidationContext context = context(
            "unapproved_read",
            true
        );

        assertThatThrownBy(() -> validator.validate(context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimum observation count");
    }

    @Test
    void rejectsObservationThatIsNotGroundingUsable() {
        SpecialistGroundingValidationContext context = context(
            "get_service_status",
            false
        );

        assertThatThrownBy(() -> validator.validate(context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimum observation count");
    }

    private SpecialistGroundingValidationContext context(
        String action,
        boolean groundingUsable
    ) {
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .data(Map.of(
                "readActionResolution",
                Map.of(
                    "executedActions",
                    List.of(Map.of(
                        "action", action,
                        "success", true,
                        "groundingUsable", groundingUsable,
                        "evidenceSummary", Map.of("status", "HEALTHY")
                    ))
                )
            ))
            .build();
        RequestedCapabilityProfile capabilities =
            new RequestedCapabilityProfile(
                false,
                Set.of(),
                Set.of("get_service_status", "list_recent_incidents"),
                Set.of("get_service_status", "list_recent_incidents"),
                Set.of()
            );
        SpecialistGroundingSpec specification = new SpecialistGroundingSpec(
            SpecialistGroundingRequirement.REQUIRED,
            false,
            List.of(new SpecialistGroundingSourceSpec(
                SpecialistGroundingSourceType.ANY_REQUESTABLE_READ_ACTION,
                null,
                1,
                List.of(),
                true
            )),
            List.of()
        );
        return new SpecialistGroundingValidationContext(
            result,
            List.of(),
            specification,
            capabilities
        );
    }
}
