package ai.fabric.intent;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentExtractionPostProcessorTargetBoundReadTest {

    @Test
    void preservesGroundingReadWithoutInventingInformationRetrieval() {
        AIActionRegistry registry = registryFor(targetBoundRead());
        IntentExtractionPostProcessor processor = new IntentExtractionPostProcessor(registry);
        Intent intent = actionIntent(false, Map.of());

        MultiIntentResponse result = processor.postProcess(
            MultiIntentResponse.builder().intents(List.of(intent)).build(),
            "Which current options are suitable for commuting?"
        );

        Intent processed = result.getIntents().getFirst();
        assertThat(processed.getType()).isEqualTo(IntentType.ACTION);
        assertThat(processed.getAction()).isEqualTo("get_catalog_item");
        assertThat(processed.getActionParams()).isEmpty();
        assertThat(processed.getRequiresRetrieval()).isFalse();
        assertThat(processed.getRequiresGeneration()).isFalse();
        assertThat(processed.getVectorSpace()).isNull();
        assertThat(result.getMetadata()).doesNotContainKey("normalization");
    }

    @Test
    void preservesMissingRetrievalDecisionForSchemaValidation() {
        AIActionRegistry registry = registryFor(targetBoundRead());
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .intent("get_catalog_item")
            .action("get_catalog_item")
            .requiresGeneration(false)
            .requiresTargetResolution(false)
            .build();

        new IntentExtractionPostProcessor(registry).postProcess(
            MultiIntentResponse.builder().intents(List.of(intent)).build(),
            "Show the selected item"
        );

        assertThat(intent.getRequiresRetrieval()).isNull();
    }

    @Test
    void preservesGroundingReadWhenTargetResolutionWasRequested() {
        AIActionRegistry registry = registryFor(targetBoundRead());
        Intent intent = actionIntent(true, Map.of());

        new IntentExtractionPostProcessor(registry).postProcess(
            MultiIntentResponse.builder().intents(List.of(intent)).build(),
            "Explain this item"
        );

        assertThat(intent.getType()).isEqualTo(IntentType.ACTION);
        assertThat(intent.getAction()).isEqualTo("get_catalog_item");
    }

    @Test
    void preservesWriteActionsWithTrustedTargetParameters() {
        AIActionMetaData write = targetBoundRead();
        write.setAccessMode(ActionAccessMode.WRITE_ONLY);
        AIActionRegistry registry = registryFor(write);
        Intent intent = actionIntent(false, Map.of());

        new IntentExtractionPostProcessor(registry).postProcess(
            MultiIntentResponse.builder().intents(List.of(intent)).build(),
            "Update this item"
        );

        assertThat(intent.getType()).isEqualTo(IntentType.ACTION);
    }

    @Test
    void preservesReadsWhoseHiddenParameterComesFromOwnedResourceContext() {
        AIActionMetaData metadata = targetBoundRead();
        metadata.setParameterSchemas(Map.of(
            "itemId", hiddenSchema("OWNED_RESOURCE")
        ));
        AIActionRegistry registry = registryFor(metadata);
        Intent intent = actionIntent(false, Map.of());

        new IntentExtractionPostProcessor(registry).postProcess(
            MultiIntentResponse.builder().intents(List.of(intent)).build(),
            "Show my saved item"
        );

        assertThat(intent.getType()).isEqualTo(IntentType.ACTION);
    }

    @Test
    void preservesReadWhenAVisibleRequiredParameterIsAlsoMissing() {
        AIActionMetaData metadata = targetBoundRead();
        metadata.setRequiredParameters(Set.of("itemId", "format"));
        metadata.setParameterSchemas(Map.of(
            "itemId", hiddenSchema("ATTACHMENT_METADATA"),
            "format", AIActionParamSchema.builder().name("format").required(true).askUser(true).build()
        ));
        AIActionRegistry registry = registryFor(metadata);
        Intent intent = actionIntent(false, Map.of());

        new IntentExtractionPostProcessor(registry).postProcess(
            MultiIntentResponse.builder().intents(List.of(intent)).build(),
            "Export this item"
        );

        assertThat(intent.getType()).isEqualTo(IntentType.ACTION);
    }

    private static AIActionRegistry registryFor(AIActionMetaData metadata) {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findMetadata("get_catalog_item")).thenReturn(Optional.of(metadata));
        return registry;
    }

    private static Intent actionIntent(boolean requiresTargetResolution, Map<String, Object> params) {
        return Intent.builder()
            .type(IntentType.ACTION)
            .intent("get_catalog_item")
            .action("get_catalog_item")
            .actionParams(params)
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .requiresTargetResolution(requiresTargetResolution)
            .build();
    }

    private static AIActionMetaData targetBoundRead() {
        return AIActionMetaData.builder()
            .name("get_catalog_item")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .groundingVectorSpaces(List.of("catalog-item"))
            .requiredParameters(Set.of("itemId"))
            .parameterSchemas(Map.of("itemId", hiddenSchema("ATTACHMENT_METADATA")))
            .build();
    }

    private static AIActionParamSchema hiddenSchema(String source) {
        return AIActionParamSchema.builder()
            .name("itemId")
            .required(true)
            .visibility("INTERNAL")
            .askUser(false)
            .evidenceBound(true)
            .resolveFrom(Map.of("source", source))
            .build();
    }

}
