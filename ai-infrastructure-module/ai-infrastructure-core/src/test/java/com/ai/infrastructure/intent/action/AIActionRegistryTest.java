package com.ai.infrastructure.intent.action;

import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.assertj.core.util.Throwables;

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
}
