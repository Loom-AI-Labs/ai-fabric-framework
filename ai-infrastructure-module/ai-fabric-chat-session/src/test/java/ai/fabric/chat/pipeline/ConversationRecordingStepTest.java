package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.ActionTargetRef;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.privacy.pii.PIIDetectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationRecordingStepTest {

    @Test
    void shouldRecordTurnUsingSanitizedMessageAndRedactedQuery() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        PIIDetectionService piiDetectionService = mock(PIIDetectionService.class);
        when(piiDetectionService.analyze(anyString())).thenReturn(PIIDetectionResult.builder()
            .piiDetected(true)
            .processedQuery("My ssn is [REDACTED]")
            .detections(java.util.List.of())
            .build());

        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(piiDetectionService);

        ConversationRecordingStep step = new ConversationRecordingStep(chatSessionService, properties, provider);

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("raw message")
            .build();

        PipelineContext context = PipelineContext.from("My ssn is 123-45-6789", orchestrationContext)
            .toBuilder()
            .intentResult(result)
            .sanitizedPayload(Map.of("message", "safe message"))
            .build();

        step.process(context);

        verify(chatSessionService).recordTurn(
            "conv-1",
            "user-1",
            "My ssn is [REDACTED]",
            "safe message",
            Map.of("_resultType", "INFORMATION_PROVIDED")
        );
    }

    @Test
    void shouldNotRecordTurnForNeverPersistQuery() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ConversationRecordingStep step = new ConversationRecordingStep(chatSessionService, properties, provider);

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("correlation-1")
            .metadata(Map.of(OrchestrationContextMetadataKeys.QUERY_PERSISTENCE_MODE, "NEVER_PERSIST"))
            .build();

        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("one-time answer")
            .build();

        PipelineContext context = PipelineContext.from("Explain this once", orchestrationContext)
            .toBuilder()
            .intentResult(result)
            .sanitizedPayload(Map.of("message", "one-time answer"))
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated).isSameAs(context);
        verify(chatSessionService, never()).recordTurn(anyString(), anyString(), anyString(), anyString(), any());
        verify(chatSessionService, never()).mergeSessionMetadata(anyString(), anyString(), any());
    }

    @Test
    void shouldPreferTypedNeverPersistencePolicy() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ConversationRecordingStep step = new ConversationRecordingStep(
            chatSessionService,
            properties,
            provider
        );
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conversation-1")
            .build();
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "Explain this once",
            orchestrationContext,
            null,
            ConversationPersistencePolicy.NEVER
        )).toBuilder()
            .intentResult(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("one-time answer")
                .build())
            .sanitizedPayload(Map.of("message", "one-time answer"))
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated).isSameAs(context);
        verify(chatSessionService, never()).recordTurn(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldNotRecordReadOnlyConversationBeforeGatewayValidation() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        ConversationRecordingStep step = new ConversationRecordingStep(
            chatSessionService,
            properties,
            provider
        );
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conversation-1")
            .build();
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "Internal specialist envelope",
            orchestrationContext,
            null,
            ConversationPersistencePolicy.READ_ONLY,
            null,
            "Why am I blocked?"
        )).toBuilder()
            .intentResult(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("{\"assessment\":\"BLOCKED\"}")
                .build())
            .sanitizedPayload(Map.of(
                "message",
                "{\"assessment\":\"BLOCKED\"}"
            ))
            .build();

        step.process(context);

        verify(chatSessionService, never()).recordTurn(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any()
        );
    }

    @Test
    void shouldRecordConversationInputInsteadOfInternalModelEnvelope() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ConversationRecordingStep step = new ConversationRecordingStep(
            chatSessionService,
            properties,
            provider
        );
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conversation-1")
            .build();
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "APPROVED SPECIALIST\nInternal instructions\nAPPLICATION INPUT\nWhy am I blocked?",
            orchestrationContext,
            null,
            ConversationPersistencePolicy.CONVERSATION,
            null,
            "Why am I blocked?"
        )).toBuilder()
            .intentResult(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("A payment method is missing.")
                .build())
            .sanitizedPayload(Map.of("message", "A payment method is missing."))
            .build();

        step.process(context);

        verify(chatSessionService).recordTurn(
            "conversation-1",
            "user-1",
            "Why am I blocked?",
            "A payment method is missing.",
            Map.of("_resultType", "INFORMATION_PROVIDED")
        );
    }

    @Test
    void shouldRecordAgainstBoundConversationOwnerInsteadOfTrustedSubject() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ConversationRecordingStep step = new ConversationRecordingStep(
            chatSessionService,
            properties,
            provider
        );
        TrustedExecutionContext trustedContext = new TrustedExecutionContext(
            new ExecutionPrincipal(
                "service-principal",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.APPLICATION,
            "tenant-1",
            "resolver-app",
            Set.of("specialist:account-resolver@1"),
            "correlation-1",
            null
        );
        PipelineContext context = PipelineContext.from(
            new OrchestrationRequest(
                "Internal specialist envelope",
                OrchestrationContext.builder()
                    .userId("support-agent-7")
                    .conversationId("conversation-1")
                    .build(),
                trustedContext,
                ConversationPersistencePolicy.CONVERSATION,
                null,
                "Why am I blocked?"
            )
        ).toBuilder()
            .intentResult(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("A payment method is missing.")
                .build())
            .sanitizedPayload(Map.of("message", "A payment method is missing."))
            .build();

        step.process(context);

        verify(chatSessionService).recordTurn(
            "conversation-1",
            "support-agent-7",
            "Why am I blocked?",
            "A payment method is missing.",
            Map.of("_resultType", "INFORMATION_PROVIDED")
        );
    }

    @Test
    void shouldPersistResolvedTargetsIntoSessionMetadata() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        when(chatSessionService.getSession(anyString(), anyString())).thenReturn(ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(java.util.List.of(
                ai.fabric.chat.domain.ChatTurn.builder().build()
            ))
            .createdAt(java.time.LocalDateTime.now())
            .lastInteractionAt(java.time.LocalDateTime.now())
            .build());

        ConversationRecordingStep step = new ConversationRecordingStep(chatSessionService, properties, provider);

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .attachmentsNormalized(java.util.List.of(NormalizedAttachment.builder()
                .contentText("snippet")
                .build()))
            .build();

        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("ok")
            .build();

        PipelineContext context = PipelineContext.from("summarize this", orchestrationContext)
            .toBuilder()
            .intentResult(result)
            .resolvedTargets(java.util.List.of(ResolvedTarget.builder()
                .id(null)
                .vectorSpace("product")
                .contentText("snippet")
                .metadata(Map.of("sku", "SKU-1"))
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build()))
            .sanitizedPayload(Map.of("message", "ok"))
            .build();

        step.process(context);

        verify(chatSessionService).mergeSessionMetadata(eq("conv-1"), eq("user-1"), argThat(map -> {
            if (!(map.containsKey("lastResolvedTargets") && map.containsKey("lastResolvedTargetsTurnIndex"))) {
                return false;
            }
            Object rawTargets = map.get("lastResolvedTargets");
            if (!(rawTargets instanceof java.util.List<?> list) || list.isEmpty()) {
                return false;
            }
            Object first = list.getFirst();
            if (!(first instanceof Map<?, ?> entry)) {
                return false;
            }
            return !entry.containsKey("id")
                && "snippet".equals(entry.get("contentText"))
                && "REQUEST_ATTACHMENTS".equals(entry.get("originSource"));
        }));
    }

    @Test
    void shouldPersistWriteActionPinnedTargetsAndAttachments() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        when(chatSessionService.getSession(anyString(), anyString())).thenReturn(ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(java.util.List.of(
                ai.fabric.chat.domain.ChatTurn.builder().build()
            ))
            .createdAt(java.time.LocalDateTime.now())
            .lastInteractionAt(java.time.LocalDateTime.now())
            .build());

        ConversationRecordingStep step = new ConversationRecordingStep(chatSessionService, properties, provider);

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .attachmentsNormalized(java.util.List.of(NormalizedAttachment.builder()
                .contentText("selected")
                .vectorSpace("product")
                .metadata(Map.of("sku", "SKU-1"))
                .build()))
            .build();

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("ok")
            .data(ActionResultContracts.object(Map.of("cartId", 1)))
            .pinnedTargets(java.util.List.of(
                new ActionTargetRef("1", "cart", "active cart", Map.of("cartId", "1"))
            ))
            .build();

        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("ok")
            .data(Map.of(
                "action", "list_products",
                "metadata", AIActionMetaData.builder().name("add_to_cart").accessMode(ActionAccessMode.WRITE_ONLY).build(),
                "actionResult", actionResult
            ))
            .build();

        PipelineContext context = PipelineContext.from("list products", orchestrationContext)
            .toBuilder()
            .intentResult(result)
            .resolvedTargets(java.util.List.of(ResolvedTarget.builder()
                .id(null)
                .vectorSpace("product")
                .contentText("selected")
                .metadata(Map.of("sku", "SKU-1"))
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build()))
            .sanitizedPayload(Map.of("message", "ok"))
            .build();

        step.process(context);

        verify(chatSessionService).mergeSessionMetadata(eq("conv-1"), eq("user-1"), argThat(map -> {
            Object raw = map.get("lastResolvedTargets");
            if (!(raw instanceof java.util.List<?> list) || list.isEmpty()) {
                return false;
            }
            if (list.size() < 2) {
                return false;
            }
            Object first = list.get(0);
            Object second = list.get(1);
            if (!(first instanceof Map<?, ?> firstEntry) || !(second instanceof Map<?, ?> secondEntry)) {
                return false;
            }
            return "1".equals(firstEntry.get("id"))
                && "ACTION_RESULT_ITEMS".equals(firstEntry.get("originSource"))
                && "selected".equals(secondEntry.get("contentText"))
                && "REQUEST_ATTACHMENTS".equals(secondEntry.get("originSource"));
        }));
    }

    @Test
    void shouldPersistReadActionPinnedTargets() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        when(chatSessionService.getSession(anyString(), anyString())).thenReturn(ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(java.util.List.of(
                ai.fabric.chat.domain.ChatTurn.builder().build()
            ))
            .createdAt(java.time.LocalDateTime.now())
            .lastInteractionAt(java.time.LocalDateTime.now())
            .build());

        ConversationRecordingStep step = new ConversationRecordingStep(chatSessionService, properties, provider);

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("ok")
            .data(ActionResultContracts.list(java.util.List.of(Map.of("sku", "SKU-ALI-52056"))))
            .pinnedTargets(java.util.List.of(
                new ActionTargetRef("SKU-ALI-52056", "product", "Alienware m18 R2", Map.of("sku", "SKU-ALI-52056"))
            ))
            .build();

        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("ok")
            .data(Map.of(
                "action", "search_products",
                "metadata", AIActionMetaData.builder().name("search_products").accessMode(ActionAccessMode.READ).build(),
                "actionResult", actionResult
            ))
            .build();

        PipelineContext context = PipelineContext.from("search products for alienware", orchestrationContext)
            .toBuilder()
            .intentResult(result)
            .sanitizedPayload(Map.of("message", "ok"))
            .build();

        step.process(context);

        verify(chatSessionService).mergeSessionMetadata(eq("conv-1"), eq("user-1"), argThat(map -> {
            Object raw = map.get("lastResolvedTargets");
            if (!(raw instanceof java.util.List<?> list) || list.size() != 1) {
                return false;
            }
            Object first = list.getFirst();
            if (!(first instanceof Map<?, ?> entry)) {
                return false;
            }
            return "SKU-ALI-52056".equals(entry.get("id"))
                && "product".equals(entry.get("vectorSpace"))
                && "ACTION_RESULT_ITEMS".equals(entry.get("originSource"));
        }));
    }
}
