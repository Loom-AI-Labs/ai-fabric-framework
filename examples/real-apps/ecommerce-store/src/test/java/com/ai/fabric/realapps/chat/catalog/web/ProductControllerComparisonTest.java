package com.ai.fabric.realapps.chat.catalog.web;

import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.ComparisonHighlights;
import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.ProductComparisonResponse;
import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.ProductReadModel;
import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.SimilarProductMatch;
import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.SimilarProductsResponse;
import com.ai.fabric.realapps.chat.catalog.service.ProductComparisonService;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerComparisonTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private ProductComparisonService productComparisonService;

    @Test
    void similarEndpointReturnsStructuredMatches() throws Exception {
        ProductReadModel reference = new ProductReadModel(
            1L,
            "SKU-0001",
            "Alpha Backpack",
            "desc",
            "Bags",
            List.of("travel", "carry-on"),
            null,
            new BigDecimal("99.00"),
            "USD",
            4,
            4.6,
            12
        );
        ProductReadModel candidate = new ProductReadModel(
            2L,
            "SKU-0002",
            "Beta Backpack",
            "desc",
            "Bags",
            List.of("travel"),
            null,
            new BigDecimal("109.00"),
            "USD",
            8,
            4.8,
            16
        );
        when(productComparisonService.findSimilarProducts("SKU-0001", 5)).thenReturn(
            new SimilarProductsResponse(
                reference,
                List.of(new SimilarProductMatch(candidate, 58, List.of("same-category", "shared-tags:travel"))),
                1
            )
        );

        mockMvc.perform(get("/api/products/similar").param("sku", "SKU-0001").param("limit", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referenceProduct.sku").value("SKU-0001"))
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.items[0].product.sku").value("SKU-0002"))
            .andExpect(jsonPath("$.items[0].similarityScore").value(58));
    }

    @Test
    void compareEndpointReturnsStructuredHighlights() throws Exception {
        ProductReadModel reference = new ProductReadModel(
            1L,
            "SKU-0001",
            "Alpha Backpack",
            "desc",
            "Bags",
            List.of("travel", "carry-on"),
            null,
            new BigDecimal("99.00"),
            "USD",
            4,
            4.6,
            12
        );
        ProductReadModel candidate = new ProductReadModel(
            2L,
            "SKU-0002",
            "Beta Backpack",
            "desc",
            "Bags",
            List.of("travel"),
            null,
            new BigDecimal("109.00"),
            "USD",
            8,
            4.8,
            16
        );
        when(productComparisonService.compareProducts("SKU-0001", "SKU-0002")).thenReturn(
            new ProductComparisonResponse(
                reference,
                candidate,
                true,
                List.of("travel"),
                new ComparisonHighlights(new BigDecimal("10.00"), "SKU-0001", 0.2d, "SKU-0002", 4, "SKU-0002"),
                List.of("SKU-0001 is cheaper by 10.00.")
            )
        );

        mockMvc.perform(get("/api/products/compare")
                .param("referenceSku", "SKU-0001")
                .param("comparisonSku", "SKU-0002"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referenceProduct.sku").value("SKU-0001"))
            .andExpect(jsonPath("$.comparisonProduct.sku").value("SKU-0002"))
            .andExpect(jsonPath("$.sameCategory").value(true))
            .andExpect(jsonPath("$.highlights.cheaperSku").value("SKU-0001"));
    }
}
