package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AttachmentsProperties;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.attachment.NormalizedAttachment;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentPromptAugmentationStepTest {

    @Test
    void shouldInjectPinnedTargetsContextBlock() {
        AttachmentsProperties properties = new AttachmentsProperties();
        AttachmentPromptAugmentationStep step = new AttachmentPromptAugmentationStep(properties);

        NormalizedAttachment attachment = NormalizedAttachment.builder()
            .id("1")
            .vectorSpace("product")
            .metadata(Map.of("sku", "SKU-1"))
            .build();

        NormalizedAttachment idLess = NormalizedAttachment.builder()
            .id(null)
            .vectorSpace("policy")
            .contentText("Return policy excerpt")
            .build();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId("demo-user")
            .attachmentsNormalized(List.of(attachment, idLess))
            .build();

        PipelineContext ctx = PipelineContext.from("hello", orch);
        PipelineContext updated = step.process(ctx);

        assertThat(updated.getEffectiveQuery()).isEqualTo("hello");
        assertThat(updated.getPinnedTargetsContext()).startsWith("ATTACHMENTS (user context; pinned targets):");
        assertThat(updated.getPinnedTargetsContext()).contains("ref=att#1");
        assertThat(updated.getPinnedTargetsContext()).contains("vectorSpace=product");
        assertThat(updated.getPinnedTargetsContext()).contains("id=1");
        assertThat(updated.getPinnedTargetsContext()).contains("metadata={sku=SKU-1}");
        assertThat(updated.getPinnedTargetsContext()).contains("ref=att#2");
        assertThat(updated.getPinnedTargetsContext()).doesNotContain("id=null");
        assertThat(updated.getPinnedTargetsContext()).contains("Return policy excerpt");
        assertThat(updated.getMetadata()).containsKey("attachmentsPrompt");
    }

    @Test
    void shouldIncludeAttachmentIdWhenVectorSpaceIsMissing() {
        AttachmentsProperties properties = new AttachmentsProperties();
        AttachmentPromptAugmentationStep step = new AttachmentPromptAugmentationStep(properties);

        NormalizedAttachment attachment = NormalizedAttachment.builder()
            .id("85")
            .vectorSpace(null)
            .contentText("some content")
            .build();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId("demo-user")
            .attachmentsNormalized(List.of(attachment))
            .build();

        PipelineContext ctx = PipelineContext.from("summarize this", orch);
        PipelineContext updated = step.process(ctx);

        assertThat(updated.getPinnedTargetsContext()).contains("id=85");
        assertThat(updated.getPinnedTargetsContext()).doesNotContain("vectorSpace=");
    }
}
