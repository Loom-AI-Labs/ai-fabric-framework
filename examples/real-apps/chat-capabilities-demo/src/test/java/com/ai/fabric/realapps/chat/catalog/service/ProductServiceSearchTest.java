package com.ai.fabric.realapps.chat.catalog.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.repo.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductServiceSearchTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final ProductService service = new ProductService(productRepository, aiCoreService);

    @Test
    void searchHydratesValidResultIdsAndSkipsMalformedRows() {
        Product first = product(1L, "SKU-0001");
        Product second = product(2L, "SKU-0002");
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(
                Map.of("entityId", "1"),
                Map.of("id", "bad"),
                Map.of(),
                Map.of("id", 2L)
            ))
            .build());
        when(productRepository.findById(1L)).thenReturn(Optional.of(first));
        when(productRepository.findById(2L)).thenReturn(Optional.of(second));

        List<Product> results = service.search("travel", 10, 0.2d);

        assertThat(results).extracting(Product::getId).containsExactly(1L, 2L);
    }

    @Test
    void searchReturnsEmptyListWhenAiSearchHasNoResults() {
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .build());

        assertThat(service.search("missing", 5, 0.2d)).isEmpty();
    }

    private static Product product(long id, String sku) {
        Product product = new Product();
        product.setId(id);
        product.setSku(sku);
        product.setName("Product " + id);
        product.setDescription("Description " + id);
        return product;
    }
}
