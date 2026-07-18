package com.ai.fabric.realapps.chat.cart.action;

import com.ai.fabric.realapps.chat.cart.service.CartService;
import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AddToCartActionHandlerTest {

    private final CartService cartService = mock(CartService.class);
    private final ProductService productService = mock(ProductService.class);
    private final AddToCartActionHandler handler = new AddToCartActionHandler(cartService, productService);

    @Test
    void confirmationDisplaysProductTitleWhenSkuResolvesToProduct() {
        Product product = new Product();
        product.setSku("SKU-LAP-9001");
        product.setName("Alienware M18 R2 Gaming Laptop");
        when(productService.findBySkuOrName("SKU-LAP-9001")).thenReturn(Optional.of(product));

        String confirmation = handler.confirm(List.of(new AddToCartActionHandler.CartItemInput("SKU-LAP-9001", 1)));

        assertThat(confirmation).isEqualTo("Add 1 × Alienware M18 R2 Gaming Laptop to your cart?");
    }

    @Test
    void confirmationKeepsExactTitleWhenTitleIsAlreadyProvided() {
        Product product = new Product();
        product.setSku("SKU-LAP-9001");
        product.setName("Alienware M18 R2 Gaming Laptop");
        when(productService.findBySkuOrName("Alienware M18 R2 Gaming Laptop")).thenReturn(Optional.of(product));

        String confirmation = handler.confirm(List.of(new AddToCartActionHandler.CartItemInput("Alienware M18 R2 Gaming Laptop", null)));

        assertThat(confirmation).isEqualTo("Add 1 × Alienware M18 R2 Gaming Laptop to your cart?");
    }
}
