package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpdateAddressOutcomeProjectorTest {

    private final UpdateAddressOutcomeProjector projector =
        new UpdateAddressOutcomeProjector();

    @Test
    void successfulProjectionExposesOnlyApplicationApprovedFields() {
        ActionResult result = ActionResult.builder()
            .success(true)
            .message("Internal handler message")
            .data(ActionResultContracts.object(Map.of(
                "subscriptionId",
                "private-subscription-id",
                "streetAddress",
                "private street",
                "addressType",
                "BILLING",
                "isValidated",
                true
            )))
            .build();

        var projected = projector.project(result);

        assertThat(projected.actionName()).isEqualTo("update_address");
        assertThat(projected.message())
            .isEqualTo("The account address was updated successfully.");
        assertThat(projected.data())
            .containsOnlyKeys("updated", "addressType", "isValidated")
            .containsEntry("updated", true)
            .containsEntry("addressType", "BILLING")
            .containsEntry("isValidated", true)
            .doesNotContainKeys("subscriptionId", "streetAddress");
        assertThat(projected.toString())
            .doesNotContain("private-subscription-id", "private street");
    }

    @Test
    void failedProjectionDoesNotExposeHandlerFailureDetails() {
        ActionResult result = ActionResult.builder()
            .success(false)
            .message("Database contained private-subscription-id")
            .errorCode("UPDATE_ADDRESS_FAILED")
            .build();

        var projected = projector.project(result);

        assertThat(projected.message())
            .isEqualTo("The address could not be updated.");
        assertThat(projected.data()).containsOnlyKeys("updated")
            .containsEntry("updated", false);
        assertThat(projected.toString())
            .doesNotContain("private-subscription-id");
    }
}
