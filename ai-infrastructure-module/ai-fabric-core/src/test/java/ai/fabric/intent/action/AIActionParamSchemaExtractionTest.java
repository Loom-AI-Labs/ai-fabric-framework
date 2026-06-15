package ai.fabric.intent.action;

import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.convert.ConversionService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AIActionParamSchemaExtractionTest {

    @AIAction(
        name = "batch_demo",
        description = "Batch demo action",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    static class BatchDemoAction {

        record Item(
            String sku,
            Integer quantity
        ) {
        }

        @ActionExecute
        public ActionResult execute(
            @Param(value = "items", description = "Items to process", required = true, batchTargets = true) List<Item> items
        ) {
            return ActionResult.builder()
                .success(true)
                .message("ok")
                .build();
        }
    }

    @Test
    void initialize_shouldExtractBatchArraySchema() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(BatchDemoAction.class);
        ctx.refresh();

        AIActionRegistry registry = new AIActionRegistry(
            ctx,
            ctx.getBeanProvider(ConversionService.class),
            ctx.getBeanProvider(com.fasterxml.jackson.databind.ObjectMapper.class),
            ctx.getBeanProvider(AIActionRegistryContributor.class)
        );
        registry.initialize();

        AIActionMetaData meta = registry.findMetadata("batch_demo").orElseThrow();
        assertThat(meta.getParameterSchemas()).containsKey("items");

        AIActionParamSchema schema = meta.getParameterSchemas().get("items");
        assertThat(schema.getType()).isEqualTo(AIActionParamType.ARRAY);
        assertThat(schema.getBatchTargets()).isTrue();
        assertThat(schema.getItems()).isNotNull();
        assertThat(schema.getItems().getType()).isEqualTo(AIActionParamType.OBJECT);
        assertThat(schema.getItems().getProperties()).containsKeys("sku", "quantity");
    }
}
