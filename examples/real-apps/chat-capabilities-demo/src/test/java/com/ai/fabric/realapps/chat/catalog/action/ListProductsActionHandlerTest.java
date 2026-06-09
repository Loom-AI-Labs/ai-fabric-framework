package com.ai.fabric.realapps.chat.catalog.action;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionListPayload;
import com.ai.infrastructure.intent.action.ActionResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ListProductsActionHandlerTest {

    @Test
    void returnsMatchesWhenFound() {
        ProductService productService = mock(ProductService.class);
        when(productService.searchByNameAndCategory("iphone", 10)).thenReturn(List.of(product(1L, "iPhone 15", "Phones", "999.00")));

        ListProductsActionHandler handler = new ListProductsActionHandler(productService);
        ActionResult result = handler.execute("iphone", new ActionContext(null, null));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Matching products");
        assertThat(result.getData()).isInstanceOf(ActionListPayload.class);
        ActionListPayload payload = (ActionListPayload) result.getData();
        assertThat(payload.getCount()).isEqualTo(1);
        assertThat(payload.getItems()).hasSize(1);
    }

    @Test
    void fallsBackToTrendingWhenNoMatches() {
        ProductService productService = mock(ProductService.class);
        when(productService.searchByNameAndCategory("nonexistent", 10)).thenReturn(List.of());
        when(productService.trending(10)).thenReturn(List.of(product(2L, "Galaxy Tab", "Tablets", "799.00")));

        ListProductsActionHandler handler = new ListProductsActionHandler(productService);
        ActionResult result = handler.execute("nonexistent", new ActionContext(null, null));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Trending products");
        assertThat(result.getData()).isInstanceOf(ActionListPayload.class);
        ActionListPayload payload = (ActionListPayload) result.getData();
        assertThat(payload.getCount()).isEqualTo(1);
        assertThat(payload.getItems()).hasSize(1);
    }

    private static Product product(Long id, String name, String category, String price) {
        Product p = new Product();
        p.setId(id);
        p.setSku("SKU-" + id);
        p.setName(name);
        p.setDescription("desc");
        p.setCategory(category);
        p.setTags(null);
        p.setPrice(new BigDecimal(price));
        p.setCurrency("USD");
        p.setInStockQty(10);
        return p;
    }
}
