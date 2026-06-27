package com.ai.fabric.realapps.chat.cart.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CartSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesItemsWithoutRecursiveCartBackReference() throws Exception {
        Cart cart = new Cart();
        cart.setId(1L);
        cart.setUserId("u1");
        cart.setStatus(Cart.Status.ACTIVE);
        cart.setCurrency("USD");

        CartItem item = new CartItem();
        item.setId(10L);
        item.setCart(cart);
        item.setSku("SKU-1");
        item.setProductName("Smoke item");
        item.setQuantity(1);
        item.setUnitPrice(new BigDecimal("10.00"));
        item.setTotalPrice(new BigDecimal("10.00"));
        cart.getItems().add(item);

        String json = objectMapper.writeValueAsString(cart);

        assertThat(json).contains("\"items\"");
        assertThat(json).contains("\"sku\":\"SKU-1\"");
        assertThat(json).doesNotContain("\"cart\"");
    }
}
