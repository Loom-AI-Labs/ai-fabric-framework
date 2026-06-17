package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionContextLookupSupportTest {

    @Test
    void shouldCollectActionParameterNamesInStableContractOrder() {
        Map<String, AIActionParamSchema> schemas = new LinkedHashMap<>();
        schemas.put(" orderId ", AIActionParamSchema.builder().build());
        schemas.put("reason", AIActionParamSchema.builder().build());
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("note", "Note");
        parameters.put("reason", "Reason");

        var names = ActionContextLookupSupport.collectActionParameterNames(AIActionMetaData.builder()
            .parameterSchemas(schemas)
            .requiredParameters(new LinkedHashSet<>(List.of("shopperSessionId", "orderId")))
            .parameters(parameters)
            .build());

        assertThat(names).containsExactly("orderId", "reason", "shopperSessionId", "note");
        assertThatThrownBy(() -> names.add("other"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldResolveCandidateKeysAndMetadataValues() {
        Map<String, Object> resolveFrom = new LinkedHashMap<>();
        resolveFrom.put("metadataKeys", List.of("handle", "resource_id"));
        resolveFrom.put("field", "externalId");

        assertThat(ActionContextLookupSupport.resolveParamCandidateKeys("product_id", resolveFrom))
            .containsExactly("handle", "resource_id", "externalId", "product_id", "productId", "productID", "productGid");
        assertThat(ActionContextLookupSupport.attachmentContextCandidateKeys("cart_id"))
            .containsExactly("cart_id", "cartId", "cartID", "cartGid");
        assertThat(ActionContextLookupSupport.metadataValueByCandidateKeys(
            Map.of("ProductID", " gid://product/1 "),
            List.of("product_id", "productID")
        )).isEqualTo("gid://product/1");
    }

    @Test
    void shouldResolveResultPathsAndNestedValues() {
        Map<String, Object> resolveFrom = Map.of(
            "resultPaths", List.of("data.items.0.id", "payload.id"),
            "resultPath", "id"
        );

        assertThat(ActionContextLookupSupport.resolveResultPaths("product_id", resolveFrom))
            .containsExactly("data.items.0.id", "payload.id", "id", "product_id", "productId", "productID", "productGid");

        Map<String, Object> root = Map.of(
            "data", Map.of("items", List.of(
                Map.of("id", "first", "sku", "sku-1"),
                Map.of("id", "second")
            )),
            "payload", Map.of("ProductID", "product-1")
        );

        assertThat(ActionContextLookupSupport.valueByPath(root, "data.items.1.id")).isEqualTo("second");
        assertThat(ActionContextLookupSupport.valueByPath(root, "data.items.sku")).isEqualTo("sku-1");
        assertThat(ActionContextLookupSupport.valueByPath(root, "payload.productid")).isEqualTo("product-1");
        assertThat(ActionContextLookupSupport.valueByPath(root, "data.items.9.id")).isNull();
    }

    @Test
    void shouldResolveTextAndCandidateValues() {
        assertThat(ActionContextLookupSupport.valueByCandidateKeys(
            Map.of("CartID", "cart-1"),
            List.of("cart_id", "cartID")
        )).isEqualTo("cart-1");
        assertThat(ActionContextLookupSupport.stringObject(" value ")).isEqualTo("value");
        assertThat(ActionContextLookupSupport.stringObject("   ")).isNull();
        assertThat(ActionContextLookupSupport.firstTextObject(null, " ", "first")).isEqualTo("first");
    }
}
