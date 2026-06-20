package ai.fabric.intent.action.connector.registry.service;

import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.ActionResultPresentationHint;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.intent.action.connector.ConnectorActionParamDefinition;
import ai.fabric.intent.action.connector.ConnectorActionPostPolicyDefinition;
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
            "a",
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
            "a",
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
            "a",
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

    @Test
    void validate_rejectsUnsupportedDisplayNameForDbRegistry() {
        ConnectorActionDefinition def = action(List.of(param("sku")), builder -> builder.displayName = "Create Order");

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("displayName");
    }

    @Test
    void validate_rejectsUnsupportedRuntimeConfigForDbRegistry() {
        ConnectorActionDefinition def = action(List.of(param("sku")), builder -> {
            builder.adapterType = "mcp-tool";
            builder.execution = Map.of("tool", "createOrder");
            builder.mcpServers = Map.of("commerce", Map.of("baseUrl", "http://localhost:8080"));
        });

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("adapter/execution runtime config");
    }

    @Test
    void validate_rejectsUnsupportedPostPoliciesForDbRegistry() {
        ConnectorActionDefinition def = action(List.of(param("sku")), builder ->
            builder.postPolicies = List.of(new ConnectorActionPostPolicyDefinition("webhook", "order.created", "ORDER_CREATED"))
        );

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("postPolicies");
    }

    @Test
    void validate_rejectsNestedParamSchemasThatCannotRoundTrip() {
        ConnectorActionParamDefinition items = new ConnectorActionParamDefinition(
            "items",
            "Items",
            AIActionParamType.ARRAY,
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
            param("sku"),
            Map.of(),
            List.of(),
            false,
            List.of(),
            null
        );
        ConnectorActionDefinition def = action(List.of(items));

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("flat params");
    }

    @Test
    void validate_rejectsEvidenceParamMetadataThatCannotRoundTrip() {
        ConnectorActionParamDefinition selectedProduct = new ConnectorActionParamDefinition(
            "selectedProduct",
            "Selected product",
            AIActionParamType.STRING,
            true,
            false,
            null,
            List.of(),
            null,
            null,
            null,
            "hidden",
            true,
            Map.of("source", "grounding"),
            false,
            null,
            Map.of(),
            List.of(),
            true,
            List.of("product.id"),
            "fail"
        );
        ConnectorActionDefinition def = action(List.of(selectedProduct));

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("assistant-resolution/evidence");
    }

    @Test
    void validate_rejectsOverlongActionNameBeforePersistence() {
        String name = "a".repeat(129);
        ConnectorActionDefinition def = action(List.of(), builder -> {
            builder.name = name;
            builder.displayName = name;
        });

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("action.name")
            .hasMessageContaining("128");
    }

    @Test
    void validate_rejectsOverlongParamValuesBeforePersistence() {
        ConnectorActionParamDefinition param = param("sku", builder -> {
            builder.description = "d".repeat(1025);
            builder.pattern = "p".repeat(513);
            builder.allowedValues = List.of("A");
            builder.defaultValue = "B";
        });
        ConnectorActionDefinition def = action(List.of(param));

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("param.description")
            .hasMessageContaining("1024");
    }

    @Test
    void validate_rejectsInvalidRegexPattern() {
        ConnectorActionDefinition def = action(List.of(param("sku", builder -> builder.pattern = "[")));

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("param.pattern")
            .hasMessageContaining("sku");
    }

    @Test
    void validate_rejectsDuplicateAllowedValuesBeforePersistence() {
        ConnectorActionDefinition def = action(List.of(param("status", builder ->
            builder.allowedValues = List.of("OPEN", "OPEN")
        )));

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate allowed value")
            .hasMessageContaining("status");
    }

    @Test
    void validate_rejectsOverlongDefaultValueBeforePersistence() {
        ConnectorActionDefinition def = action(List.of(param("quantity", builder ->
            builder.defaultValue = "1".repeat(513)
        )));

        assertThatThrownBy(() -> validator.validate(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("param.defaultValue")
            .hasMessageContaining("512");
    }

    private ConnectorActionParamDefinition param(String name) {
        return param(name, builder -> {
        });
    }

    private ConnectorActionParamDefinition param(String name, ParamBuilderCustomizer customizer) {
        ParamBuilder builder = new ParamBuilder(name);
        customizer.customize(builder);
        return new ConnectorActionParamDefinition(
            builder.name,
            builder.description,
            builder.type,
            builder.required,
            builder.batchTargets,
            builder.pattern,
            builder.allowedValues,
            builder.min,
            builder.max,
            builder.defaultValue,
            null,
            null,
            Map.of(),
            builder.sensitive,
            null,
            Map.of(),
            List.of(),
            false,
            List.of(),
            null
        );
    }

    private ConnectorActionDefinition action(List<ConnectorActionParamDefinition> params) {
        return action(params, builder -> {
        });
    }

    private ConnectorActionDefinition action(List<ConnectorActionParamDefinition> params, ActionBuilderCustomizer customizer) {
        ActionBuilder builder = new ActionBuilder();
        builder.params = params;
        customizer.customize(builder);
        return builder.build();
    }

    private interface ActionBuilderCustomizer {
        void customize(ActionBuilder builder);
    }

    private interface ParamBuilderCustomizer {
        void customize(ParamBuilder builder);
    }

    private static final class ActionBuilder {
        private String name = "a";
        private String displayName = "a";
        private List<ConnectorActionParamDefinition> params = List.of();
        private List<ConnectorActionPostPolicyDefinition> postPolicies = List.of();
        private String adapterType;
        private Map<String, Object> execution = Map.of();
        private Map<String, Object> mcpServers = Map.of();

        private ConnectorActionDefinition build() {
            return new ConnectorActionDefinition(
                name,
                displayName,
                "desc",
                "cat",
                ActionAccessMode.READ,
                false,
                null,
                params,
                false,
                true,
                false,
                ActionResultPresentationHint.DEFAULT,
                null,
                null,
                null,
                postPolicies,
                null,
                adapterType,
                execution,
                mcpServers
            );
        }
    }

    private static final class ParamBuilder {
        private final String name;
        private String description = "SKU";
        private AIActionParamType type = AIActionParamType.STRING;
        private boolean required = true;
        private boolean batchTargets = false;
        private String pattern;
        private List<String> allowedValues = List.of();
        private Long min;
        private Long max;
        private Object defaultValue;
        private boolean sensitive = false;

        private ParamBuilder(String name) {
            this.name = name;
        }
    }
}
