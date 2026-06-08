package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AttachmentsProperties;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.attachment.NormalizedAttachment;
import com.ai.infrastructure.intent.orchestration.attachment.OrchestrationAttachment;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.privacy.pii.PIIDetectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttachmentNormalizationStepTest {

    @Test
    void shouldNormalizeAttachmentsAndKeepIdLessAttachments() {
        AttachmentsProperties properties = new AttachmentsProperties();
        properties.setMaxAttachments(2);
        properties.setMaxContentTextChars(10);
        properties.setMaxMetadataKeys(2);
        properties.setMaxMetadataValueChars(5);
        properties.setMaxIdChars(10);
        properties.setMaxVectorSpaceChars(20);

        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> piiProvider = mock(ObjectProvider.class);
        when(piiProvider.getIfAvailable()).thenReturn(null);

        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeBaseOverviewService> kbProvider = mock(ObjectProvider.class);
        when(kbProvider.getIfAvailable()).thenReturn(null);

        AttachmentNormalizationStep step = new AttachmentNormalizationStep(properties, piiProvider, kbProvider);

        OrchestrationAttachment a1 = OrchestrationAttachment.builder()
            .id(" 1 ")
            .vectorSpace("product")
            .contentText("hello world")
            .metadata(Map.of(
                "sku", "SKU-123456",
                "rank", 7,
                "ignored", List.of("not-scalar")
            ))
            .source(" ui-card ")
            .build();

        OrchestrationAttachment a2 = OrchestrationAttachment.builder()
            .id(null)
            .vectorSpace("product")
            .metadata(Map.of("price", "123456"))
            .build();

        OrchestrationAttachment a3 = OrchestrationAttachment.builder()
            .id("3")
            .vectorSpace("product")
            .build();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId("demo")
            .attachments(List.of(a1, a2, a3))
            .build();

        PipelineContext ctx = PipelineContext.from("q", orch);
        PipelineContext updated = step.process(ctx);

        OrchestrationContext out = updated.getOrchestrationContext();
        assertThat(out.getAttachmentsNormalized()).hasSize(2);

        NormalizedAttachment first = out.getAttachmentsNormalized().getFirst();
        assertThat(first.getId()).isEqualTo("1");
        assertThat(first.getVectorSpace()).isEqualTo("product");
        assertThat(first.getContentText()).isEqualTo("hello worl");
        assertThat(first.isContentTextTruncated()).isTrue();
        assertThat(first.getSource()).isEqualTo("ui-card");
        assertThat(first.getMetadata()).containsEntry("sku", "SKU-1");
        assertThat(first.getMetadata()).containsEntry("rank", "7");
        assertThat(first.getMetadata()).doesNotContainKey("ignored");

        NormalizedAttachment second = out.getAttachmentsNormalized().get(1);
        assertThat(second.getId()).isNull();
        assertThat(second.getVectorSpace()).isEqualTo("product");
        assertThat(second.getMetadata()).containsEntry("price", "12345");

        assertThat(updated.getMetadata()).containsKey("attachments");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) updated.getMetadata().get("attachments");
        assertThat(meta.get("providedCount")).isEqualTo(3);
        assertThat(meta.get("acceptedCount")).isEqualTo(2);
        assertThat(meta.get("truncated")).isEqualTo(true);
    }

    @Test
    void shouldRespectAttachmentLimitsWhenMaxIsZero() {
        AttachmentsProperties properties = new AttachmentsProperties();
        properties.setMaxAttachments(0);

        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> piiProvider = mock(ObjectProvider.class);
        when(piiProvider.getIfAvailable()).thenReturn(null);

        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeBaseOverviewService> kbProvider = mock(ObjectProvider.class);
        when(kbProvider.getIfAvailable()).thenReturn(null);

        AttachmentNormalizationStep step = new AttachmentNormalizationStep(properties, piiProvider, kbProvider);

        OrchestrationAttachment a1 = OrchestrationAttachment.builder()
            .id("1")
            .vectorSpace("product")
            .build();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId("demo")
            .attachments(List.of(a1))
            .build();

        PipelineContext ctx = PipelineContext.from("q", orch);
        PipelineContext updated = step.process(ctx);

        assertThat(updated.getOrchestrationContext().getAttachmentsNormalized()).isEmpty();

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) updated.getMetadata().get("attachments");
        assertThat(meta.get("truncated")).isEqualTo(true);
    }

    @Test
    void shouldAcceptAttachmentWhenVectorSpaceIsMissing() {
        AttachmentsProperties properties = new AttachmentsProperties();
        properties.setMaxAttachments(10);

        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> piiProvider = mock(ObjectProvider.class);
        when(piiProvider.getIfAvailable()).thenReturn(null);

        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeBaseOverviewService> kbProvider = mock(ObjectProvider.class);
        when(kbProvider.getIfAvailable()).thenReturn(null);

        AttachmentNormalizationStep step = new AttachmentNormalizationStep(properties, piiProvider, kbProvider);

        OrchestrationAttachment attachment = OrchestrationAttachment.builder()
            .id("att-1")
            .vectorSpace(null)
            .contentText("hello")
            .build();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId("demo")
            .attachments(List.of(attachment))
            .build();

        PipelineContext ctx = PipelineContext.from("q", orch);
        PipelineContext updated = step.process(ctx);

        assertThat(updated.getOrchestrationContext().getAttachmentsNormalized()).hasSize(1);
        NormalizedAttachment normalized = updated.getOrchestrationContext().getAttachmentsNormalized().getFirst();
        assertThat(normalized.getId()).isEqualTo("att-1");
        assertThat(normalized.getVectorSpace()).isNull();
        assertThat(normalized.isContentTextTruncated()).isFalse();
    }

    @Test
    void shouldAcceptAttachmentWhenIdIsMissingButContentTextIsPresent() {
        AttachmentsProperties properties = new AttachmentsProperties();
        properties.setMaxAttachments(10);

        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> piiProvider = mock(ObjectProvider.class);
        when(piiProvider.getIfAvailable()).thenReturn(null);

        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeBaseOverviewService> kbProvider = mock(ObjectProvider.class);
        when(kbProvider.getIfAvailable()).thenReturn(null);

        AttachmentNormalizationStep step = new AttachmentNormalizationStep(properties, piiProvider, kbProvider);

        OrchestrationAttachment attachment = OrchestrationAttachment.builder()
            .id(null)
            .vectorSpace("policy")
            .contentText("Return policy excerpt")
            .build();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId("demo")
            .attachments(List.of(attachment))
            .build();

        PipelineContext ctx = PipelineContext.from("q", orch);
        PipelineContext updated = step.process(ctx);

        assertThat(updated.getOrchestrationContext().getAttachmentsNormalized()).hasSize(1);
        NormalizedAttachment normalized = updated.getOrchestrationContext().getAttachmentsNormalized().getFirst();
        assertThat(normalized.getId()).isNull();
        assertThat(normalized.getVectorSpace()).isEqualTo("policy");
        assertThat(normalized.getContentText()).isEqualTo("Return policy excerpt");
    }
}
