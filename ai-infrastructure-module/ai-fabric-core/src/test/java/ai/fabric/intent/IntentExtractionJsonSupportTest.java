package ai.fabric.intent;

import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.llm.structured.StructuredJsonProviderHints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentExtractionJsonSupportTest {

    private final IntentExtractionJsonSupport jsonSupport = new IntentExtractionJsonSupport(new ObjectMapper());

    @Test
    void parseResponseUsesSharedStructuredExtractorForWrappedJson() {
        String content = """
            Provider note before the payload.
            ```json
            {
              "intents": [
                {
                  "type": "INFORMATION",
                  "intent": "refund_policy",
                  "requiresRetrieval": true
                }
              ]
            }
            ```
            Provider note after the payload.
            """;

        MultiIntentResponse response = jsonSupport.parseResponse(content);

        assertThat(response.getIntents()).hasSize(1);
        assertThat(response.getIntents().getFirst().getType()).isEqualTo(IntentType.INFORMATION);
        assertThat(response.getIntents().getFirst().getIntent()).isEqualTo("refund_policy");
    }

    @Test
    void parsePayloadKeepsTolerantIntentJsonBehavior() {
        String content = """
            {
              // Provider comment remains tolerated.
              "intents": [
                {
                  "type": "ACTION",
                  "intent": "cancel_subscription",
                  "action": "cancel_subscription",
                }
              ],
            }
            """;

        MultiIntentResponse response = jsonSupport.parsePayload(content, MultiIntentResponse.class);

        assertThat(response.getIntents()).hasSize(1);
        assertThat(response.getIntents().getFirst().getAction()).isEqualTo("cancel_subscription");
    }

    @Test
    void parsePayloadFailsClosedWhenNoJsonPayloadExists() {
        assertThatThrownBy(() -> jsonSupport.parsePayload("not-json", MultiIntentResponse.class))
            .isInstanceOf(AIServiceException.class)
            .hasMessageContaining("Unable to parse intent extraction response");
    }

    @Test
    void jsonOnlyResponseParametersComeFromStructuredHints() {
        assertThat(jsonSupport.jsonOnlyResponseParameters())
            .isEqualTo(StructuredJsonProviderHints.jsonObjectResponseParameters());
    }
}
