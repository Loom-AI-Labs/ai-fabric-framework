package com.ai.infrastructure.intent.action;

import com.ai.infrastructure.intent.action.annotation.Param;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.support.DefaultConversionService;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionMethodArgumentBinderTest {

    @Test
    void shouldRejectFractionalNumberForIntegerParameter() throws Exception {
        ActionMethodArgumentBinder binder = new ActionMethodArgumentBinder(new DefaultConversionService(), new ObjectMapper());
        Method method = DemoAction.class.getDeclaredMethod("budget", Integer.class);

        assertThatThrownBy(() -> binder.bind(method, Map.of("budget", 1.5d), null))
            .isInstanceOf(ActionMethodArgumentBinder.InvalidActionParameterException.class)
            .hasMessageContaining("must be an integer");
    }

    @Test
    void shouldEnforceNumericBoundsWithoutLongTruncation() throws Exception {
        ActionMethodArgumentBinder binder = new ActionMethodArgumentBinder(new DefaultConversionService(), new ObjectMapper());
        Method method = DemoAction.class.getDeclaredMethod("price", Double.class);

        assertThatThrownBy(() -> binder.bind(method, Map.of("price", 1.5d), null))
            .isInstanceOf(ActionMethodArgumentBinder.InvalidActionParameterException.class)
            .hasMessageContaining("must be <= 1");
    }

    @Test
    void shouldAcceptIntegralDoubleForIntegerParameter() throws Exception {
        ActionMethodArgumentBinder binder = new ActionMethodArgumentBinder(new DefaultConversionService(), new ObjectMapper());
        Method method = DemoAction.class.getDeclaredMethod("budget", Integer.class);

        Object[] args = binder.bind(method, Map.of("budget", 1.0d), null);

        assertThat(args).containsExactly(1);
    }

    @Test
    void shouldTreatRequiredPlaceholderTextAsMissing() throws Exception {
        ActionMethodArgumentBinder binder = new ActionMethodArgumentBinder(new DefaultConversionService(), new ObjectMapper());
        Method method = DemoAction.class.getDeclaredMethod("shipping", String.class);

        assertThatThrownBy(() -> binder.bind(method, Map.of("shippingAddress", "New shipping address (required)"), null))
            .isInstanceOf(ActionMethodArgumentBinder.MissingActionParameterException.class)
            .hasMessageContaining("shippingAddress");
    }

    @Test
    void shouldWrapCoercionFailuresAsInvalidActionParameter() throws Exception {
        ActionMethodArgumentBinder binder = new ActionMethodArgumentBinder(new DefaultConversionService(), new ObjectMapper());
        Method method = DemoAction.class.getDeclaredMethod("budget", Integer.class);

        assertThatThrownBy(() -> binder.bind(method, Map.of("budget", "not-a-number"), null))
            .isInstanceOf(ActionMethodArgumentBinder.InvalidActionParameterException.class)
            .hasMessageContaining("cannot be converted");
    }

    static final class DemoAction {
        void budget(@Param(value = "budget", required = true, min = 1, max = 1) Integer budget) {
        }

        void price(@Param(value = "price", required = true, max = 1) Double price) {
        }

        void shipping(@Param(value = "shippingAddress", required = true) String shippingAddress) {
        }
    }
}

