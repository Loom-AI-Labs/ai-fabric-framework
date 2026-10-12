package com.subscription.hub.action.handler;

import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountResolverActionContractTest {

    @Test
    void paymentMethodRequiresExactlyFourDigits() {
        Param last4 = parameter(
            UpdatePaymentMethodActionHandler.class,
            "last4"
        );

        assertThat(last4.required()).isTrue();
        assertThat(last4.pattern()).isEqualTo("\\d{4}");
    }

    @Test
    void addressAndRefundContractsExposeClosedAllowedValuesAndBounds() {
        Param addressType = parameter(UpdateAddressActionHandler.class, "addressType");
        Param amount = parameter(RequestRefundActionHandler.class, "amount");
        Param resolutionType = parameter(
            RequestRefundActionHandler.class,
            "resolutionType"
        );

        assertThat(addressType.allowedValues())
            .containsExactly("BILLING", "SHIPPING");
        assertThat(amount.required()).isTrue();
        assertThat(amount.min()).isEqualTo(1);
        assertThat(resolutionType.allowedValues())
            .containsExactly("REFUND", "ACCOUNT_CREDIT");
    }

    private Param parameter(Class<?> actionType, String name) {
        Method execute = Arrays.stream(actionType.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(ActionExecute.class))
            .findFirst()
            .orElseThrow();
        return Arrays.stream(execute.getParameters())
            .map(parameter -> annotatedParameter(parameter, name))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseThrow();
    }

    private Param annotatedParameter(Parameter parameter, String name) {
        Param annotation = parameter.getAnnotation(Param.class);
        return annotation != null && name.equals(annotation.value())
            ? annotation
            : null;
    }
}
