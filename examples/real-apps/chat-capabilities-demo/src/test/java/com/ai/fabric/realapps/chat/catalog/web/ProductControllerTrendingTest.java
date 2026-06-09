package com.ai.fabric.realapps.chat.catalog.web;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTrendingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @Test
    void trendingEndpointReturnsProducts() throws Exception {
        Product p = new Product();
        p.setId(1L);
        p.setSku("IPHONE-15");
        p.setName("iPhone 15");
        p.setDescription("desc");
        p.setCategory("Phones");
        p.setPrice(new BigDecimal("999.00"));
        p.setCurrency("USD");
        p.setInStockQty(5);

        when(productService.trending(10)).thenReturn(List.of(p));

        mockMvc.perform(get("/api/products/trending").param("limit", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].sku").value("IPHONE-15"));
    }
}
