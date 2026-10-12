package com.ai.fabric.realapps.chat.catalog.action;

import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionGroundingSufficiency;
import ai.fabric.intent.action.ActionListPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.annotation.AIAction;
import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchProductsActionHandlerTest {

    @Test
    void exposesReviewedProductGroundingContract() {
        AIAction contract = SearchProductsActionHandler.class.getAnnotation(AIAction.class);

        assertThat(contract.readActionResolutionEligible()).isTrue();
        assertThat(contract.groundingVectorSpaces()).containsExactly("product");
    }

    @Test
    void matchingLiveProductsAreExplicitlySufficient() {
        ProductService service = mock(ProductService.class);
        when(service.searchByNameAndCategory("gaming laptop", 10))
            .thenReturn(List.of(product()));

        ActionResult result = new SearchProductsActionHandler(service)
            .execute("gaming laptop", new ActionContext(null, null));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getGroundingSufficiency())
            .isEqualTo(ActionGroundingSufficiency.SUFFICIENT);
        assertThat(((ActionListPayload) result.getData()).getCount()).isEqualTo(1);
    }

    @Test
    void emptyLiveSearchIsInsufficientAndDoesNotSubstituteTrendingProducts() {
        ProductService service = mock(ProductService.class);
        when(service.searchByNameAndCategory("missing item", 10))
            .thenReturn(List.of());

        ActionResult result = new SearchProductsActionHandler(service)
            .execute("missing item", new ActionContext(null, null));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("No products found");
        assertThat(result.getGroundingSufficiency())
            .isEqualTo(ActionGroundingSufficiency.INSUFFICIENT);
        assertThat(((ActionListPayload) result.getData()).getCount()).isZero();
        verify(service, never()).trending(10);
    }

    private Product product() {
        Product product = new Product();
        product.setId(1L);
        product.setSku("SKU-LAP-9001");
        product.setName("Alienware M18 R2 Gaming Laptop");
        product.setDescription("High performance gaming laptop");
        product.setCategory("Laptops");
        product.setPrice(new BigDecimal("2499.00"));
        product.setCurrency("USD");
        product.setInStockQty(17);
        return product;
    }
}
