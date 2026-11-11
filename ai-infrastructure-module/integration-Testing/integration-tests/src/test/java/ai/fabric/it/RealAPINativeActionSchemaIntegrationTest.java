package ai.fabric.it;

import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.connector.McpActionExecutor;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.it.support.RealAPITestSupport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real extraction and confirmation; only the business receiver is synthetic. */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("real-api-test")
@Import(RealAPINativeActionSchemaIntegrationTest.FixtureConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "ai.actions.sources[0].type=FILE",
    "ai.actions.sources[0].path=classpath:actions/native-schema-realapi.yml",
    "ai.actions.builtin.vector-management.enabled=false",
    "ai.chat.enabled=true",
    "ai.chat.auto-create-sessions=true",
    "ai.intent-extraction.progressive.enabled=false",
    "ai.providers.llm-provider=openai",
    "ai.providers.embedding-provider=openai",
    "ai.providers.onnx.enabled=false",
    "ai.providers.enable-fallback=false",
    "ai.vector-db.type=memory",
    "ai.pii-detection.enabled=false",
    "ai.behavior.enabled=false",
    "ai.service.features.enable-embeddings=false",
    "ai.service.features.enable-search=false",
    "ai.service.features.enable-rag=false",
    "ai.orchestration.default-mode=native-fixture",
    "ai.orchestration.modes.native-fixture.actions-enabled=true",
    "ai.orchestration.modes.native-fixture.actions-preferred=true",
    "ai.orchestration.modes.native-fixture.retrieval-enabled=false",
    "ai.orchestration.modes.native-fixture.suggestions-enabled=false",
    "ai.orchestration.modes.native-fixture.read-action-resolution.enabled=true",
    "ai.orchestration.modes.native-fixture.read-action-resolution.require-allowlist=true",
    "ai.orchestration.modes.native-fixture.read-action-resolution.allowed-read-actions[0]=native_read_setting",
    "ai.orchestration.modes.native-fixture.read-action-resolution.allowed-read-actions[1]=native_read_private_setting",
    "ai.orchestration.modes.native-fixture.read-action-resolution.max-total-actions=1",
    "ai.orchestration.modes.native-fixture.read-action-resolution.rag-cooperation-mode=NONE"
})
class RealAPINativeActionSchemaIntegrationTest {
    static {
        RealAPITestSupport.ensureProviderConfigured();
        RealAPITestSupport.ensureLLMProviderSet();
    }

    @Autowired private RAGOrchestrator orchestrator;
    @Autowired private AIActionRegistry registry;
    @Autowired private RecordingMcpExecutor receiver;
    @Autowired private ChatSessionService chatSessions;
    @Value("${ai.providers.openai.model}") private String model;
    private final AtomicInteger queries = new AtomicInteger();

    @BeforeAll
    void requireRealOpenAiAndNativeCatalogue() {
        assertThat(StringUtils.hasText(System.getProperty("OPENAI_API_KEY"))
            || StringUtils.hasText(System.getenv("OPENAI_API_KEY")))
            .as("This gate requires a privately injected real OpenAI key; no fake LLM or skipped qualification")
            .isTrue();
        assertThat(registry.findMetadata("native_read_setting").orElseThrow().getInputSchema()).isNotEmpty();
        assertThat(registry.findMetadata("native_read_setting").orElseThrow().getGroundingVectorSpaces()).isEmpty();
        System.out.printf("Native schema qualification: provider=openai model=%s maxQueries=8 syntheticReceiver=true%n", model);
    }

    @BeforeEach
    void clearSyntheticReceiver() {
        receiver.calls.clear();
    }

    @Test
    void publicReadWithoutVectorsPreservesNestedNullableArguments() {
        var context = OrchestrationContext.builder().sessionId("public-" + UUID.randomUUID()).build();
        var result = query("Use native_read_setting with requestKey public-sample and options "
            + "{\"threshold\":0.5,\"note\":null}. Execute this public read; do not search documents.", context);
        assertThat(result.isSuccess()).as("Public READ result: %s", result.getType()).isTrue();
        assertThat(receiver.calls).hasSize(1);
        var call = receiver.calls.getFirst();
        assertThat(call.action()).isEqualTo("native_read_setting");
        assertThat(call.mode()).isEqualTo(ActionAccessMode.READ);
        assertThat(call.parameters()).containsEntry("requestKey", "public-sample");
        assertThat(((Map<?, ?>) call.parameters().get("options")).containsKey("note")).isTrue();
        assertThat(((Map<?, ?>) call.parameters().get("options")).get("note")).isNull();
    }

    @Test
    void privateReadResolvesTrustedContextAndPreservesPublicNull() {
        var context = signedInContext();
        var result = query("Read my private synthetic setting with native_read_private_setting for requestKey private-sample. "
            + "Use explicitly null selection. This is a read, not a preference change.", context);
        assertThat(result.isSuccess()).as("Private READ result: %s", result.getType()).isTrue();
        assertThat(receiver.calls).hasSize(1);
        assertThat(receiver.calls.getFirst().action()).isEqualTo("native_read_private_setting");
        assertThat(receiver.calls.getFirst().parameters()).containsEntry("selection", null)
            .containsEntry("scopeKey", context.getUserId());
    }

    @Test
    void collectsRequiredNullableArgumentAndResolvesTrustedContextBeforeConfirmedWrite() {
        var context = signedInContext();
        var missing = query("Save my preference using native_save_preference for requestKey collection-sample. "
            + "I have not chosen a selection yet; ask me for it before preparing the change.", context);
        assertThat(missing.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(receiver.calls).isEmpty();
        assertThatThrownBy(() -> chatSessions.getSession(context.getConversationId(), "different-fixture-owner"))
            .isInstanceOf(ChatSessionAccessDeniedException.class);
        var proposal = query("Clear the selection: selection must explicitly be JSON null, not an omitted field. "
            + "Keep requestKey collection-sample. Prepare the change for confirmation.", context);
        assertThat(proposal.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(receiver.calls).isEmpty();
        var confirmed = query("Yes, confirm and save that change.", context);
        assertThat(confirmed.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(confirmed.isSuccess()).isTrue();
        assertThat(receiver.calls).hasSize(1);
        assertThat(receiver.calls.getFirst().parameters()).containsEntry("selection", null)
            .containsEntry("scopeKey", context.getUserId()).doesNotContainKey("optional");
    }

    @Test
    void rejectedNativeWriteNeverReachesReceiver() {
        var context = signedInContext();
        var proposal = query("Use native_save_preference for requestKey rejected-sample with selection blue. "
            + "Prepare the change and request my confirmation.", context);
        assertThat(proposal.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        var rejected = query("No. Reject and cancel this change; do not save it.", context);
        assertThat(rejected.isSuccess()).isTrue();
        assertThat(rejected.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(receiver.calls).isEmpty();
    }

    @Test
    void declaredNoConfirmationWriteUsesTrustedBindingWithoutAskingUser() {
        var context = signedInContext();
        var result = query("Execute native_save_without_confirmation with requestKey direct-sample. "
            + "Its declared policy permits this synthetic operation without confirmation.", context);
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(receiver.calls).hasSize(1);
        assertThat(receiver.calls.getFirst().parameters()).containsEntry("scopeKey", context.getUserId());
    }

    private OrchestrationResult query(String text, OrchestrationContext context) {
        assertThat(queries.incrementAndGet()).as("Bounded eight-query native qualification").isLessThanOrEqualTo(8);
        var result = orchestrator.orchestrate(text, context);
        assertThat(result).isNotNull();
        System.out.printf("Native schema real-provider turn %d: type=%s success=%s receiverCalls=%d%n",
            queries.get(), result.getType(), result.isSuccess(), receiver.calls.size());
        return result;
    }

    private OrchestrationContext signedInContext() {
        return OrchestrationContext.builder().userId("native-owner-" + UUID.randomUUID())
            .conversationId("native-chat-" + UUID.randomUUID()).build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean RecordingMcpExecutor nativeSchemaMcpExecutor() { return new RecordingMcpExecutor(); }

        @Bean
        ChatSessionAccessControlPolicy nativeSchemaChatPolicy() {
            // Test-owned admission; ChatSessionService still checks the persisted conversation owner.
            return new ChatSessionAccessControlPolicy() {
                @Override public boolean canCreateConversation(String ownerId) { return StringUtils.hasText(ownerId); }
                @Override public boolean canAccessConversation(String ownerId, String conversationId) {
                    return valid(ownerId, conversationId);
                }
                @Override public boolean canRecordTurn(String ownerId, String conversationId) {
                    return valid(ownerId, conversationId);
                }
                @Override public boolean canDeleteConversation(String ownerId, String conversationId) {
                    return valid(ownerId, conversationId);
                }
                private boolean valid(String ownerId, String conversationId) {
                    return StringUtils.hasText(ownerId) && StringUtils.hasText(conversationId);
                }
            };
        }
    }

    static final class RecordingMcpExecutor implements McpActionExecutor {
        final List<Call> calls = new ArrayList<>();

        @Override public boolean isAvailable() { return true; }

        @Override
        public ActionResult execute(String actionId, ActionAccessMode mode, Map<String, Object> params,
                                    ActionContext context, Map<String, Object> config) {
            calls.add(new Call(actionId, mode, Collections.unmodifiableMap(new LinkedHashMap<>(params))));
            return ActionResult.builder().success(true).message("Synthetic fixture accepted the setting.")
                .data(ActionPayload.object(Map.of("accepted", true))).build();
        }
    }

    record Call(String action, ActionAccessMode mode, Map<String, Object> parameters) {}
}
