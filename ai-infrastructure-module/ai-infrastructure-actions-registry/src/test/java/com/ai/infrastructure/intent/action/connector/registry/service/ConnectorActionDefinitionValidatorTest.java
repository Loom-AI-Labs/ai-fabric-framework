package com.ai.infrastructure.intent.action.connector.registry.service;

import com.ai.infrastructure.intent.action.AIActionParamType;
import com.ai.infrastructure.intent.action.ActionResultPresentationHint;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.connector.ConnectorActionDefinition;
import com.ai.infrastructure.intent.action.connector.ConnectorActionParamDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorActionDefinitionValidatorTest {

    private final ConnectorActionDefinitionValidator validator = new ConnectorActionDefinitionValidator();

    @Test
    void validate_rejectsMissingName() {
        ConnectorActionDefinition def = new ConnectorActionDefinition(
            null,
            null,
            "desc",
            "cat",
            ActionAccessMode.READ,
            false,
            null,
            List.of(),
            false,
            true,
            false,
            ActionResultPresentationHint.DEFAULT,
            null,
            null,
            null,
            List.of(),
            null
        );

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_rejectsDuplicateParamNames() {
        ConnectorActionDefinition def = new ConnectorActionDefinition(
            "a",
            "A",
            "desc",
            "cat",
            ActionAccessMode.READ,
            false,
            null,
            List.of(
                param("sku"),
                param("SKU")
            ),
            false,
            true,
            false,
            ActionResultPresentationHint.DEFAULT,
            null,
            null,
            null,
            List.of(),
            null
        );

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate");
    }

    @Test
    void validate_rejectsConfirmationTemplateUnknownPlaceholder() {
        ConnectorActionDefinition def = new ConnectorActionDefinition(
            "a",
            "A",
            "desc",
            "cat",
            ActionAccessMode.WRITE_ONLY,
            true,
            "Create order for {{missing}}?",
            List.of(param("sku")),
            false,
            false,
            false,
            ActionResultPresentationHint.STATUS,
            null,
            null,
            null,
            List.of(),
            null
        );

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("placeholder");
    }

    @Test
    void validate_acceptsConfirmationTemplatePlaceholderFallback() {
        ConnectorActionDefinition def = new ConnectorActionDefinition(
            "a",
            "A",
            "desc",
            "cat",
            ActionAccessMode.WRITE_ONLY,
            true,
            "Create order for {{sku|the selected item}}?",
            List.of(param("sku")),
            false,
            false,
            false,
            ActionResultPresentationHint.STATUS,
            null,
            null,
            null,
            List.of(),
            null
        );

        assertThatCode(() -> validator.validate(def)).doesNotThrowAnyException();
    }

    private ConnectorActionParamDefinition param(String name) {
        return new ConnectorActionParamDefinition(
            name,
            "SKU",
            AIActionParamType.STRING,
            true,
            false,
            null,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            false,
            null,
            Map.of(),
            List.of(),
            false,
            List.of(),
            null
        );
    }
}
