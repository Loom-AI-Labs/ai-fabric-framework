package ai.fabric.intent.action;

import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.ActionFacts;
import ai.fabric.intent.action.annotation.Param;
import ai.fabric.intent.orchestration.OrchestrationContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.assertj.core.util.Throwables;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIActionRegistryTest {

    @Test
    void shouldDiscoverAnnotatedActionsAndNormalizeLookup() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AIActionRegistry.class);
            context.register(CancelSubscriptionAction.class);
            context.register(UpgradeSubscriptionAction.class);
            context.refresh();

            AIActionRegistry registry = context.getBean(AIActionRegistry.class);

            assertThat(registry.findHandler("cancel_subscription")).isPresent();
            assertThat(registry.findHandler("CANCEL_SUBSCRIPTION")).isPresent();
            assertThat(registry.findHandler("cancel subscription")).isPresent();
            assertThat(registry.findHandler("cancel-subscription")).isPresent();

            AIActionMetaData meta = registry.findMetadata("cancel_subscription").orElseThrow();
            assertThat(meta.getName()).isEqualTo("cancel_subscription");
            assertThat(meta.getAccessMode()).isEqualTo(ActionAccessMode.WRITE_ONLY);
            assertThat(meta.isAnonymousAllowed()).isTrue();
            assertThat(meta.getRequiredParameters()).containsExactly("reason");
            assertThat(meta.getParameters()).containsKey("reason");
        }
    }

    @Test
    void shouldInvokeActionAllowedWithActionContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AIActionRegistry.class);
            context.register(ContextAllowedAction.class);
            context.refresh();

            AIActionRegistry registry = context.getBean(AIActionRegistry.class);
            AIActionHandler handler = registry.findHandler("context_allowed").orElseThrow();

            assertThat(handler.validateActionAllowed(new ActionContext(OrchestrationContext.forUser("allowed-user"), null)))
                .isTrue();
            assertThat(handler.validateActionAllowed(new ActionContext(OrchestrationContext.forUser("blocked-user"), null)))
                .isFalse();
        }
    }

    @Test
    void shouldInvokeActionConfirmationWithActionParameter() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AIActionRegistry.class);
            context.register(ParameterizedConfirmationAction.class);
            context.refresh();

            AIActionRegistry registry = context.getBean(AIActionRegistry.class);
            AIActionHandler handler = registry.findHandler("parameterized_confirmation").orElseThrow();

            assertThat(handler.getConfirmationMessage(Map.of("quantity", 3), new ActionContext(OrchestrationContext.forUser("user"), null)))
                .isEqualTo("Add 3 items?");
        }
    }

    @Test
    void shouldExposePromptVisibilityFromParamAnnotation() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AIActionRegistry.class);
            context.register(ContextResolvedParamAction.class);
            context.refresh();

            AIActionRegistry registry = context.getBean(AIActionRegistry.class);
            AIActionMetaData meta = registry.findMetadata("context_resolved_param").orElseThrow();

            assertThat(meta.getParameterSchemas()).containsKeys("accountId", "reason");
            assertThat(meta.getParameterSchemas().get("accountId").getAskUser()).isFalse();
            assertThat(meta.getParameterSchemas().get("accountId").getVisibility()).isEqualTo("INTERNAL");
            assertThat(meta.getParameterSchemas().get("reason").getAskUser()).isNull();
            assertThat(meta.getParameterSchemas().get("reason").getVisibility()).isNull();
        }
    }

    @Test
    void shouldBuildPostActionFactsWithActionResultAndContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AIActionRegistry.class);
            context.register(PostActionFactsAction.class);
            context.refresh();

            AIActionRegistry registry = context.getBean(AIActionRegistry.class);
            AIActionHandler handler = registry.findHandler("post_action_facts").orElseThrow();
            ActionContext actionContext = new ActionContext(OrchestrationContext.forUser("facts-user"), null);

            Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(
                ActionResult.builder().success(true).message("ok").build(),
                actionContext
            );

            assertThat(facts).isPresent();
            assertThat(facts.orElseThrow())
                .containsEntry("success", true)
                .containsEntry("userId", "facts-user");
        }
    }

    @Test
    void shouldBuildPostActionFactsWithoutArguments() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AIActionRegistry.class);
            context.register(NoArgFactsAction.class);
            context.refresh();

            AIActionRegistry registry = context.getBean(AIActionRegistry.class);
            AIActionHandler handler = registry.findHandler("no_arg_facts").orElseThrow();

            Optional<Map<String, Object>> facts = handler.buildPostActionLlmFacts(
                ActionResult.builder().success(true).message("ok").build(),
                new ActionContext(OrchestrationContext.forUser("facts-user"), null)
            );

            assertThat(facts).isPresent();
            assertThat(facts.orElseThrow())
                .containsEntry("status", "ready")
                .containsEntry("count", 2);
        }
    }

    @Test
    void shouldTreatReadWriteActionsAsSideEffectingAndNonGroundingByDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AIActionRegistry.class);
            context.register(ReadWriteAction.class);
            context.refresh();

            AIActionRegistry registry = context.getBean(AIActionRegistry.class);
            AIActionMetaData meta = registry.findMetadata("update_and_fetch_cart").orElseThrow();

            assertThat(meta.getAccessMode()).isEqualTo(ActionAccessMode.READ_WRITE);
            assertThat(meta.getSideEffectLevel()).isEqualTo(ActionSideEffectLevel.OPTIONAL_MUTATION);
            assertThat(meta.isGroundingEligible()).isFalse();
            assertThat(meta.isReadActionResolutionEligible()).isFalse();
        }
    }

    @Test
    void shouldFailFastWhenActionHasNoExecuteMethod() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(NoExecuteAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage()).contains("@ActionExecute"));
        }
    }

    @Test
    void shouldFailFastWhenActionHasMultipleExecuteMethods() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(MultipleExecuteAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage()).contains("@ActionExecute"));
        }
    }

    @Test
    void shouldFailFastWhenExecuteParameterMissingParamAnnotation() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(MissingParamAnnotationAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage()).contains("@Param"));
        }
    }

    @Test
    void shouldFailFastWhenNonReadActionOptsIntoReadActionResolution() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(ReadWritePlannerEligibleAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage())
                    .contains("readActionResolutionEligible")
                    .contains("READ")
                    .contains("READ_WRITE"));
        }
    }

    @Test
    void shouldFailFastWhenActionAllowedReturnsNonBoolean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(NonBooleanAllowedAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage())
                    .contains("@ActionAllowed")
                    .contains("boolean"));
        }
    }

    @Test
    void shouldFailFastWhenActionAllowedDeclaresActionParameter() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(ParamAllowedAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage())
                    .contains("@ActionAllowed")
                    .contains("context"));
        }
    }

    @Test
    void shouldFailFastWhenActionConfirmationReturnsNonText() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(NonTextConfirmationAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage())
                    .contains("@ActionConfirmation")
                    .contains("text"));
        }
    }

    @Test
    void shouldFailFastWhenActionConfirmationParameterMissingParamAnnotation() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(MissingParamConfirmationAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage())
                    .contains("@ActionConfirmation")
                    .contains("@Param"));
        }
    }

    @Test
    void shouldFailFastWhenActionFactsReturnsNonMap() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(NonMapFactsAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage())
                    .contains("@ActionFacts")
                    .contains("Map"));
        }
    }

    @Test
    void shouldFailFastWhenActionFactsOptionalValueIsNonMap() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(NonMapOptionalFactsAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage())
                    .contains("@ActionFacts")
                    .contains("Optional value")
                    .contains("Map"));
        }
    }

    @Test
    void shouldFailFastWhenActionFactsMapKeysAreNotString() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(NonStringKeyFactsAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage())
                    .contains("@ActionFacts")
                    .contains("keys")
                    .contains("String"));
        }
    }

    @Test
    void shouldFailFastWhenActionFactsDeclaresUnsupportedParameters() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("ai-action-registry-invalid");
            context.register(AIActionRegistry.class);
            context.register(UnsupportedFactsParamsAction.class);

            assertThatThrownBy(context::refresh)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .satisfies(ex -> assertThat(Throwables.getRootCause(ex).getMessage())
                    .contains("@ActionFacts")
                    .contains("ActionResult")
                    .contains("ActionContext"));
        }
    }

    @AIAction(
        name = "cancel_subscription",
        description = "Cancel my subscription",
        category = "subscription",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = true,
        anonymousAllowed = true
    )
    static class CancelSubscriptionAction {
        @ActionExecute
        public ActionResult execute(@Param(value = "reason", required = true, description = "Cancellation reason") String reason) {
            return ActionResult.builder()
                .success(true)
                .message("Cancelled: " + reason)
                .build();
        }
    }

    @AIAction(
        name = "upgrade_subscription",
        description = "Upgrade my subscription",
        category = "subscription",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = true
    )
    static class UpgradeSubscriptionAction {
        @ActionExecute
        public ActionResult execute(@Param(value = "plan", required = true, description = "Target plan") String plan) {
            return ActionResult.builder()
                .success(true)
                .message("Upgraded to: " + plan)
                .build();
        }
    }

    @AIAction(
        name = "context_allowed",
        description = "Action allowed by current context",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    static class ContextAllowedAction {
        @ActionAllowed
        public boolean allowed(ActionContext context) {
            return context != null && "allowed-user".equals(context.userId());
        }

        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "parameterized_confirmation",
        description = "Action with parameterized confirmation",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = true
    )
    static class ParameterizedConfirmationAction {
        @ActionConfirmation
        public String confirm(@Param(value = "quantity", required = true) Integer quantity) {
            return "Add " + quantity + " items?";
        }

        @ActionExecute
        public ActionResult execute(@Param(value = "quantity", required = true) Integer quantity) {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "context_resolved_param",
        description = "Action with context resolved parameter",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    static class ContextResolvedParamAction {
        @ActionExecute
        public ActionResult execute(
            @Param(value = "accountId", description = "Resolved account id", askUser = false, visibility = "INTERNAL") String accountId,
            @Param(value = "reason", description = "User-facing reason") String reason
        ) {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "post_action_facts",
        description = "Action with post-action facts",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    static class PostActionFactsAction {
        @ActionFacts
        public Optional<Map<String, Object>> facts(ActionResult actionResult, ActionContext context) {
            return Optional.of(Map.of(
                "success", actionResult != null && actionResult.isSuccess(),
                "userId", context != null ? context.userId() : null
            ));
        }

        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "no_arg_facts",
        description = "Action with no-arg post-action facts",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    static class NoArgFactsAction {
        @ActionFacts
        public Map<String, Object> facts() {
            return Map.of(
                "status", "ready",
                "count", 2
            );
        }

        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "update_and_fetch_cart",
        description = "Update and fetch cart",
        category = "test",
        accessMode = ActionAccessMode.READ_WRITE,
        requiresConfirmation = true
    )
    static class ReadWriteAction {
        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "no_execute",
        description = "Invalid action without @ActionExecute",
        category = "test",
        accessMode = ActionAccessMode.READ,
        requiresConfirmation = false
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class NoExecuteAction {
    }

    @AIAction(
        name = "multiple_execute",
        description = "Invalid action with multiple @ActionExecute methods",
        category = "test",
        accessMode = ActionAccessMode.READ,
        requiresConfirmation = false
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class MultipleExecuteAction {
        @ActionExecute
        public ActionResult executeA() {
            return ActionResult.builder().success(true).message("a").build();
        }

        @ActionExecute
        public ActionResult executeB() {
            return ActionResult.builder().success(true).message("b").build();
        }
    }

    @AIAction(
        name = "missing_param",
        description = "Invalid action without @Param annotations",
        category = "test",
        accessMode = ActionAccessMode.READ,
        requiresConfirmation = false
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class MissingParamAnnotationAction {
        @ActionExecute
        public ActionResult execute(String reason) {
            return ActionResult.builder()
                .success(true)
                .message("reason=" + reason)
                .build();
        }
    }

    @AIAction(
        name = "read_write_planner_eligible",
        description = "Invalid read-write planner eligible action",
        category = "test",
        accessMode = ActionAccessMode.READ_WRITE,
        requiresConfirmation = true,
        readActionResolutionEligible = true
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class ReadWritePlannerEligibleAction {
        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "non_text_confirmation",
        description = "Invalid action confirmation hook with non-text return",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = true
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class NonTextConfirmationAction {
        @ActionConfirmation
        public Boolean confirm() {
            return Boolean.TRUE;
        }

        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "missing_param_confirmation",
        description = "Invalid action confirmation hook without @Param",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = true
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class MissingParamConfirmationAction {
        @ActionConfirmation
        public String confirm(String reason) {
            return "Confirm " + reason + "?";
        }

        @ActionExecute
        public ActionResult execute(@Param("reason") String reason) {
            return ActionResult.builder()
                .success(true)
                .message("reason=" + reason)
                .build();
        }
    }

    @AIAction(
        name = "non_map_facts",
        description = "Invalid action facts hook with non-map return",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class NonMapFactsAction {
        @ActionFacts
        public String facts() {
            return "ok";
        }

        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "non_map_optional_facts",
        description = "Invalid action facts hook with non-map Optional value",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class NonMapOptionalFactsAction {
        @ActionFacts
        public Optional<String> facts() {
            return Optional.of("ok");
        }

        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "non_string_key_facts",
        description = "Invalid action facts hook with non-string map keys",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class NonStringKeyFactsAction {
        @ActionFacts
        public Optional<Map<Integer, Object>> facts() {
            return Optional.of(Map.of(1, "one"));
        }

        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "unsupported_facts_params",
        description = "Invalid action facts hook with unsupported parameters",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class UnsupportedFactsParamsAction {
        @ActionFacts
        public Map<String, Object> facts(ActionContext context) {
            return Map.of("userId", context != null ? context.userId() : null);
        }

        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "non_boolean_allowed",
        description = "Invalid action allowed hook with non-boolean return",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class NonBooleanAllowedAction {
        @ActionAllowed
        public String allowed(ActionContext context) {
            return "yes";
        }

        @ActionExecute
        public ActionResult execute() {
            return ActionResult.builder().success(true).message("ok").build();
        }
    }

    @AIAction(
        name = "param_allowed",
        description = "Invalid action allowed hook with action parameter",
        category = "test",
        accessMode = ActionAccessMode.WRITE_ONLY,
        requiresConfirmation = false
    )
    @org.springframework.context.annotation.Profile("ai-action-registry-invalid")
    static class ParamAllowedAction {
        @ActionAllowed
        public boolean allowed(@Param("reason") String reason) {
            return reason != null;
        }

        @ActionExecute
        public ActionResult execute(@Param("reason") String reason) {
            return ActionResult.builder()
                .success(true)
                .message("reason=" + reason)
                .build();
        }
    }
}
