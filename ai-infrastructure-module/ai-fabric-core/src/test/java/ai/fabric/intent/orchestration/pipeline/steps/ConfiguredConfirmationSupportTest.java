package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.IntentType;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorDecision;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorDecisionType;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorRule;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorStackPolicy;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorTrigger;
import ai.fabric.intent.orchestration.pipeline.steps.ConfirmationDecisionSupport.ConfirmationResolutionDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredConfirmationSupportTest {

    @Test
    void shouldLoadAndMatchConfiguredRulesUnlessOnceFlagIsSet() {
        ConfirmationInterceptorRule rule = rule(
            "cancel",
            List.of("Cancel_Order"),
            IntentType.CONFIRMATION_NEGATIVE,
            "handled",
            ConfirmationInterceptorDecisionType.REPLY
        );
        ConfirmationInterceptorCatalogProvider catalog = () -> List.of(rule);

        assertThat(ConfiguredConfirmationSupport.configuredConfirmationInterceptorRules(providerOf(catalog)))
            .containsExactly(rule);
        assertThat(ConfiguredConfirmationSupport.configuredConfirmationInterceptorRules(providerOf(null))).isEmpty();

        PendingAction pending = pending("cancel_order", Map.of("orderId", "ord-1"));
        assertThat(ConfiguredConfirmationSupport.findMatchingConfiguredConfirmationRule(
            List.of(rule),
            pending,
            ConfirmationResolutionDecision.NEGATIVE
        )).isSameAs(rule);

        PendingAction alreadyHandled = pending("cancel_order", Map.of("handled", true));
        assertThat(ConfiguredConfirmationSupport.findMatchingConfiguredConfirmationRule(
            List.of(rule),
            alreadyHandled,
            ConfirmationResolutionDecision.NEGATIVE
        )).isNull();
        assertThat(ConfiguredConfirmationSupport.findMatchingConfiguredConfirmationRule(
            List.of(rule),
            pending,
            ConfirmationResolutionDecision.POSITIVE
        )).isNull();
    }

    @Test
    void shouldResolveTemplatesWithStackPathsAndTypedFallbacks() {
        PendingAction pending = pending("cancel_order", Map.of("orderId", "ord-1", "count", 2));
        PendingAction previous = pending("open_cart", Map.of("cartId", "cart-1"));
        List<PendingAction> stack = List.of(pending, previous);

        assertThat(ConfiguredConfirmationSupport.resolveConfiguredConfirmationTemplateValue(
            "Cancel {{ pending.actionParams.orderId }} after {{ stack.previous.action }}",
            stack
        )).isEqualTo("Cancel ord-1 after open_cart");

        assertThat(ConfiguredConfirmationSupport.resolveConfiguredConfirmationTemplateValue(
            "{{ pending.actionParams.count }}",
            stack
        )).isEqualTo(2);

        Map<String, Object> resolved = ConfiguredConfirmationSupport.resolveConfiguredConfirmationActionParams(
            Map.of(
                "orderId", "{{ pending.actionParams.orderId }}",
                "missingBool", "{{ pending.actionParams.missing | true }}",
                "missingInt", "{{ pending.actionParams.missing | 42 }}",
                "missingDouble", "{{ pending.actionParams.missing | 3.5 }}",
                "nested", Map.of("cart", "{{ stack.previous.actionParams.cartId }}"),
                "list", List.of("{{ pending.action }}", "{{ pending.actionParams.missing }}")
            ),
            stack
        );

        assertThat(resolved)
            .containsEntry("orderId", "ord-1")
            .containsEntry("missingBool", true)
            .containsEntry("missingInt", 42)
            .containsEntry("missingDouble", 3.5d);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) resolved.get("nested");
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) resolved.get("list");
        assertThat(nested).containsEntry("cart", "cart-1");
        assertThat(list).containsExactly("cancel_order");
        assertThatThrownBy(() -> resolved.put("other", true))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldApplyStackPoliciesAndMarkPendingActions() {
        PendingAction current = pending("current_action", Map.of());
        PendingAction previous = pending("previous_action", Map.of());
        PendingAction older = pending("older_action", Map.of());

        List<PendingAction> stack = new ArrayList<>(List.of(current, previous, older));
        ConfiguredConfirmationSupport.applyConfiguredConfirmationStackPolicy(
            new ConfirmationInterceptorStackPolicy(true, List.of("previous_action")),
            stack
        );
        assertThat(stack).extracting(PendingAction::action).containsExactly("older_action");

        List<PendingAction> noPopCurrent = new ArrayList<>(List.of(current, previous, older));
        ConfiguredConfirmationSupport.applyConfiguredConfirmationStackPolicy(
            new ConfirmationInterceptorStackPolicy(false, List.of("previous_action")),
            noPopCurrent
        );
        assertThat(noPopCurrent).extracting(PendingAction::action).containsExactly("current_action", "older_action");

        PendingAction marked = ConfiguredConfirmationSupport.withBooleanPendingParam(current, "handled", true);
        assertThat(marked.actionParams()).containsEntry("handled", true);
        assertThat(marked.action()).isEqualTo(current.action());
        assertThatThrownBy(() -> marked.actionParams().put("other", true))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldNormalizeConfirmationKeys() {
        assertThat(ConfiguredConfirmationSupport.containsNormalizedConfirmationValue(
            List.of(" Cancel_Order "),
            "cancel_order"
        )).isTrue();
        assertThat(ConfiguredConfirmationSupport.normalizeConfirmationKey(" Action ")).isEqualTo("action");
    }

    private ConfirmationInterceptorRule rule(String name,
                                             List<String> pendingActions,
                                             IntentType confirmation,
                                             String onceParam,
                                             ConfirmationInterceptorDecisionType decisionType) {
        return new ConfirmationInterceptorRule(
            name,
            new ConfirmationInterceptorTrigger(pendingActions, confirmation, onceParam),
            new ConfirmationInterceptorDecision(decisionType, "reply_action", Map.of(), "message"),
            ConfirmationInterceptorStackPolicy.NONE
        );
    }

    private PendingAction pending(String action, Map<String, Object> params) {
        return new PendingAction(action, params, "description", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
