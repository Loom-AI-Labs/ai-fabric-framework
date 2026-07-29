package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.ActionDraftContinuation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionDraftContinuationSupportTest {

    @Test
    void shouldKeepOnlyPublicDraftParametersAndRequiredFieldNames() {
        AIActionMetaData metadata = addressMetadata();
        ActionDraft draft = new ActionDraft(
            "update_address",
            Map.of(
                "street", "16 Dairy Drive",
                "subscriptionId", "server-owned-subscription",
                "address", Map.of(
                    "city", "London",
                    "internalCode", "secret-routing-value"
                )
            ),
            "postalCode",
            Instant.now(),
            Instant.now()
        );

        ActionDraftContinuation continuation =
            ActionDraftContinuationSupport.continuation(draft, metadata);

        assertThat(continuation).isNotNull();
        assertThat(continuation.collectedParams())
            .containsEntry("street", "16 Dairy Drive")
            .doesNotContainKey("subscriptionId");
        assertThat(continuation.collectedParams().get("address"))
            .isEqualTo(Map.of("city", "London"));
        assertThat(continuation.missingParameters())
            .containsExactly("postalCode");
    }

    @Test
    void shouldMergeNewValuesWithServerHeldDraftAndAllowCorrections() {
        ActionDraftContinuation continuation = new ActionDraftContinuation(
            "update_address",
            Map.of(
                "street", "16 Dairy Drive",
                "address", Map.of("city", "London", "country", "UK")
            ),
            List.of("postalCode")
        );
        Intent action = action(
            "update_address",
            Map.of(
                "street", "22 New Road",
                "postalCode", "E1 6AN",
                "address", Map.of("city", "Manchester")
            )
        );
        MultiIntentResponse response = response(action);

        ActionDraftContinuationSupport.MergeOutcome outcome =
            ActionDraftContinuationSupport.merge(response, continuation);

        assertThat(outcome.matched()).isTrue();
        assertThat(action.getActionParams())
            .containsEntry("street", "22 New Road")
            .containsEntry("postalCode", "E1 6AN");
        assertThat(action.getActionParams().get("address")).isEqualTo(
            Map.of("city", "Manchester", "country", "UK")
        );
        assertThat(outcome.preservedParameterNames()).isEmpty();
        assertThat(outcome.suppliedParameterNames())
            .containsExactlyInAnyOrder("street", "postalCode", "address");
    }

    @Test
    void shouldNotEraseCollectedValueWithBlankFollowUpField() {
        ActionDraftContinuation continuation = new ActionDraftContinuation(
            "update_address",
            Map.of("street", "16 Dairy Drive"),
            List.of("postalCode")
        );
        Intent action = action(
            "update_address",
            Map.of("street", " ", "postalCode", "SW1A 1AA")
        );

        ActionDraftContinuationSupport.merge(response(action), continuation);

        assertThat(action.getActionParams())
            .containsEntry("street", "16 Dairy Drive")
            .containsEntry("postalCode", "SW1A 1AA");
    }

    @Test
    void shouldLeaveUnrelatedIntentUnchanged() {
        ActionDraftContinuation continuation = new ActionDraftContinuation(
            "update_address",
            Map.of("street", "16 Dairy Drive"),
            List.of("postalCode")
        );
        Intent information = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("get_plan")
            .directAnswer("Your plan is Pro.")
            .build();
        MultiIntentResponse response = response(information);

        ActionDraftContinuationSupport.MergeOutcome outcome =
            ActionDraftContinuationSupport.merge(response, continuation);

        assertThat(outcome.matched()).isFalse();
        assertThat(outcome.response()).isSameAs(response);
        assertThat(information.getActionParams()).isEmpty();
    }

    private AIActionMetaData addressMetadata() {
        AIActionParamSchema publicString = AIActionParamSchema.builder()
            .type(AIActionParamType.STRING)
            .askUser(true)
            .build();
        AIActionParamSchema hiddenString = AIActionParamSchema.builder()
            .type(AIActionParamType.STRING)
            .askUser(false)
            .visibility("INTERNAL")
            .build();
        AIActionParamSchema address = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of(
                "city", publicString,
                "internalCode", hiddenString
            ))
            .build();
        return AIActionMetaData.builder()
            .name("update_address")
            .parameterSchemas(Map.of(
                "street", publicString,
                "postalCode", publicString,
                "subscriptionId", hiddenString,
                "address", address
            ))
            .requiredParameters(Set.of(
                "street",
                "postalCode",
                "subscriptionId"
            ))
            .build();
    }

    private Intent action(String action, Map<String, Object> params) {
        return Intent.builder()
            .type(IntentType.ACTION)
            .action(action)
            .actionParams(params)
            .confidence(0.95d)
            .build();
    }

    private MultiIntentResponse response(Intent intent) {
        return MultiIntentResponse.builder()
            .intents(List.of(intent))
            .build();
    }
}
