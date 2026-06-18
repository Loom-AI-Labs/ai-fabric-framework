package ai.fabric.intent.action.connector;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionResultPresentationHint;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorActionCatalogLoaderTest {

    @Test
    void loadActions_shouldLoadValidContract() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/valid-actions.yml");

        List<ConnectorActionDefinition> actions = loader.loadActions(List.of(source));
        assertThat(actions).hasSize(1);

        ConnectorActionDefinition action = actions.get(0);
        assertThat(action.name()).isEqualTo("create_purchase_order");
        assertThat(action.displayName()).isEqualTo("Create Purchase Order");
        assertThat(action.accessMode()).isEqualTo(ActionAccessMode.WRITE_ONLY);
        assertThat(action.anonymousAllowed()).isTrue();
        assertThat(action.requiresConfirmation()).isTrue();
        assertThat(action.groundingEligible()).isFalse();
        assertThat(action.resultPresentationHint()).isEqualTo(ActionResultPresentationHint.STATUS);
        assertThat(action.builtInModuleId()).isEqualTo("purchase-orders");
        assertThat(action.builtInCardId()).isEqualTo("purchase-order-status");
        assertThat(action.provenance()).isNotNull();
        assertThat(action.provenance().getSourceType()).isEqualTo("ACTION_CATALOG");
        assertThat(action.provenance().getPublisher()).isEqualTo("internal-test");
        assertThat(action.confirmationMessage()).contains("{{quantity|1}}").contains("{{sku}}");
        assertThat(action.params()).hasSize(2);
        assertThat(action.postPolicies()).hasSize(1);
        assertThat(action.postPolicies().getFirst().type()).isEqualTo("webhook");
        assertThat(action.postPolicies().getFirst().targetRef()).isEqualTo("zapier_purchase_orders");
        assertThat(action.params().stream().anyMatch(p -> "sku".equals(p.name()) && p.required())).isTrue();
    }

    @Test
    void loadCatalog_shouldLoadWebhookTargets() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/valid-actions.yml");

        ConnectorActionCatalog catalog = loader.loadCatalog(List.of(source));

        assertThat(catalog.webhookTargets()).hasSize(1);
        assertThat(catalog.webhookTargets().getFirst().id()).isEqualTo("zapier_purchase_orders");
        assertThat(catalog.webhookTargets().getFirst().urlSecretRef()).isEqualTo("ZAPIER_PURCHASE_ORDERS_URL");
    }

    @Test
    void loadActions_shouldFailFastOnUnknownTemplatePlaceholder() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/invalid-placeholder.yml");

        assertThatThrownBy(() -> loader.loadActions(List.of(source)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("confirmationMessage placeholder");
    }

    @Test
    void loadActions_shouldFailFastOnMissingFile_whenNotOptional() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/does-not-exist.yml");

        assertThatThrownBy(() -> loader.loadActions(List.of(source)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Action contract file not found");
    }

    @Test
    void loadActions_shouldSkipMissingFile_whenOptional() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/does-not-exist.yml");
        source.setOptional(true);

        List<ConnectorActionDefinition> actions = loader.loadActions(List.of(source));
        assertThat(actions).isEmpty();
    }

    @Test
    void loadCatalog_shouldLoadConfirmationInterceptors() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/valid-actions-with-confirmation-interceptors.yml");

        ConnectorActionCatalog catalog = loader.loadCatalog(List.of(source));

        assertThat(catalog.actions()).hasSize(2);
        assertThat(catalog.confirmationInterceptors()).hasSize(1);
        assertThat(catalog.confirmationInterceptors().getFirst().name()).isEqualTo("cancel_to_retention_offer");
        assertThat(catalog.confirmationInterceptors().getFirst().decision().action()).isEqualTo("offer_order_discount");
    }

    @Test
    void loadCatalog_shouldRejectPromptActionTargetThatDoesNotRequireConfirmation() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/invalid-confirmation-interceptors.yml");

        assertThatThrownBy(() -> loader.loadCatalog(List.of(source)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PROMPT_ACTION")
            .hasMessageContaining("non-confirmable action");
    }

    @Test
    void loadActions_shouldRejectUnsupportedBuiltInShellMappings() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/invalid-built-in-shell-mapping.yml");

        assertThatThrownBy(() -> loader.loadActions(List.of(source)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unsupported builtInModuleId");
    }

    @Test
    void loadActions_shouldRejectRequiredPropertiesThatAreNotDeclared() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/invalid-required-properties.yml");

        assertThatThrownBy(() -> loader.loadActions(List.of(source)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("required property 'missing'")
            .hasMessageContaining("not declared in properties");
    }

    @Test
    void loadCatalog_shouldRejectUnknownWebhookTargetReference() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/invalid-webhook-target.yml");

        assertThatThrownBy(() -> loader.loadCatalog(List.of(source)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown webhook target");
    }

    @Test
    void loadActions_shouldReadReadActionResolutionEligibility() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/valid-read-actions.yml");

        List<ConnectorActionDefinition> actions = loader.loadActions(List.of(source));
        assertThat(actions).hasSize(1);

        ConnectorActionDefinition action = actions.getFirst();
        assertThat(action.name()).isEqualTo("get_policy");
        assertThat(action.accessMode()).isEqualTo(ActionAccessMode.READ);
        assertThat(action.groundingEligible()).isTrue();
        assertThat(action.readActionResolutionEligible()).isTrue();
        assertThat(action.llmFacts()).isNotNull();
        assertThat(action.llmFacts().rootPath()).isEqualTo("data");
        assertThat(action.llmFacts().copyFields()).containsExactly("query", "totalResults");
        assertThat(action.llmFacts().lists()).hasSize(1);
        ConnectorActionLlmFactsListDefinition list = action.llmFacts().lists().getFirst();
        assertThat(list.sourcePath()).isEqualTo("records");
        assertThat(list.target()).isEqualTo("records");
        assertThat(list.rankRules()).hasSize(1);
        assertThat(list.constraints()).isNotNull();
        assertThat(list.constraints().rules()).hasSize(1);
        assertThat(list.constraints().rules().getFirst().type()).isEqualTo("PARAM_NUMERIC_UPPER_BOUND");
        assertThat(list.constraints().rules().getFirst().paramPath()).isEqualTo("maxScore");
        assertThat(list.summaries()).hasSize(1);
    }

    @Test
    void loadActions_shouldPreserveMcpExecutionConfigForRuntimeTrace() {
        ConnectorActionCatalogLoader loader = new ConnectorActionCatalogLoader(new DefaultResourceLoader());

        AIActionCatalogProperties.ActionSourceProperties source = new AIActionCatalogProperties.ActionSourceProperties();
        source.setType(AIActionCatalogProperties.ActionSourceType.FILE);
        source.setPath("classpath:actions/valid-mcp-actions.yml");

        List<ConnectorActionDefinition> actions = loader.loadActions(List.of(source));
        assertThat(actions).hasSize(1);

        ConnectorActionDefinition action = actions.getFirst();
        assertThat(action.adapterType()).isEqualTo("mcp-tool");
        assertThat(action.execution()).containsKey("mcp");
        assertThat(action.mcpServers()).containsKey("inventory-mcp");
        ConnectorActionParamDefinition selectedItems = action.params().stream()
            .filter(param -> "selected_items".equals(param.name()))
            .findFirst()
            .orElseThrow();
        assertThat(selectedItems.batchTargets()).isTrue();
        assertThat(selectedItems.items()).isNotNull();
        assertThat(selectedItems.items().properties()).containsKeys("product_variant_id", "quantity");
        assertThat(selectedItems.items().properties().get("quantity").defaultValue()).isEqualTo(1);
        assertThat(selectedItems.items().requiredProperties()).containsExactly("product_variant_id", "quantity");
        AIActionMetaData metadata = ConnectorActionMetadataMapper.toMetadata(action);
        assertThat(metadata.getParameterSchemas().get("selected_items").getItems().getProperties())
            .containsKeys("product_variant_id", "quantity");
        assertThat(metadata.getParameterSchemas().get("selected_items").getItems().getProperties().get("product_variant_id").getEvidenceBound())
            .isTrue();
        assertThat(metadata.getParameterSchemas().get("selected_items").getItems().getProperties().get("product_variant_id").getEvidenceKeys())
            .containsExactly("product_variant_id", "firstAvailableVariantId");
        assertThat(metadata.getParameterSchemas().get("selected_items").getItems().getProperties().get("quantity").getDefaultValue())
            .isEqualTo(1);
        ConnectorActionParamDefinition shopperSession = action.params().stream()
            .filter(param -> "shopperSessionId".equals(param.name()))
            .findFirst()
            .orElseThrow();
        assertThat(shopperSession.visibility()).isEqualTo("INTERNAL");
        assertThat(shopperSession.askUser()).isFalse();
        assertThat(shopperSession.resolveFrom())
            .containsEntry("source", "RUNTIME_CONTEXT")
            .containsEntry("field", "sessionId");
        assertThat(metadata.getParameterSchemas().get("shopperSessionId").getVisibility()).isEqualTo("INTERNAL");
        assertThat(metadata.getParameterSchemas().get("shopperSessionId").getAskUser()).isFalse();
        assertThat(metadata.getParameterSchemas().get("shopperSessionId").getResolveFrom())
            .containsEntry("source", "RUNTIME_CONTEXT")
            .containsEntry("field", "sessionId");
        Map<String, Object> runtimeConfig = action.runtimeActionConfig();
        assertThat(runtimeConfig).containsEntry("adapterType", "mcp-tool");
        assertThat(runtimeConfig).containsKeys("execution", "mcpServers", "params");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> runtimeParams = (List<Map<String, Object>>) runtimeConfig.get("params");
        Map<String, Object> runtimeSelectedItems = runtimeParams.stream()
            .filter(param -> "selected_items".equals(param.get("name")))
            .findFirst()
            .orElseThrow();
        assertThat(runtimeSelectedItems)
            .containsEntry("type", "ARRAY")
            .containsEntry("batchTargets", true);
        Map<String, Object> runtimeShopperSession = runtimeParams.stream()
            .filter(param -> "shopperSessionId".equals(param.get("name")))
            .findFirst()
            .orElseThrow();
        assertThat(runtimeShopperSession)
            .containsEntry("visibility", "INTERNAL")
            .containsEntry("askUser", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeResolveFrom = (Map<String, Object>) runtimeShopperSession.get("resolveFrom");
        assertThat(runtimeResolveFrom)
            .containsEntry("source", "RUNTIME_CONTEXT")
            .containsEntry("field", "sessionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeItems = (Map<String, Object>) runtimeSelectedItems.get("items");
        assertThat(runtimeItems).containsEntry("type", "OBJECT");
        assertThat(runtimeItems).containsEntry("requiredProperties", List.of("product_variant_id", "quantity"));
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeProperties = (Map<String, Object>) runtimeItems.get("properties");
        assertThat(runtimeProperties).containsKeys("product_variant_id", "quantity");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeProductVariantId = (Map<String, Object>) runtimeProperties.get("product_variant_id");
        assertThat(runtimeProductVariantId)
            .containsEntry("evidenceBound", true)
            .containsEntry("evidenceFallbackPolicy", "CLARIFY");
        assertThat((List<String>) runtimeProductVariantId.get("evidenceKeys"))
            .containsExactly("product_variant_id", "firstAvailableVariantId");
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeQuantity = (Map<String, Object>) runtimeProperties.get("quantity");
        assertThat(runtimeQuantity).containsEntry("defaultValue", 1);
    }
}
