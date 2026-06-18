package com.ai.fabric.realapps.migrationcatalog.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.ai.fabric.realapps.migrationcatalog.domain.Product;
import com.ai.fabric.realapps.migrationcatalog.repo.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductCatalogServiceTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final ProductCatalogService service = new ProductCatalogService(productRepository, aiCoreService);

    @Test
    void searchHydratesValidResultIdsAndSkipsMalformedRows() {
        Product laptop = product(1L, "Laptop");
        Product dock = product(2L, "Dock");
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(
                Map.of("entityId", "1"),
                Map.of("id", "not-a-number"),
                Map.of(),
                Map.of("id", 2L)
            ))
            .build());
        when(productRepository.findById(1L)).thenReturn(Optional.of(laptop));
        when(productRepository.findById(2L)).thenReturn(Optional.of(dock));

        List<Product> results = service.search("portable dock", 10, 0.2d);

        assertThat(results).extracting(Product::getId).containsExactly(1L, 2L);
    }

    @Test
    void searchReturnsEmptyListWhenAiSearchHasNoResults() {
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .build());

        assertThat(service.search("missing", 5, 0.2d)).isEmpty();
    }

    @Test
    void seedProductsIgnoresNonPositiveCounts() {
        assertThat(service.seedProducts(0)).isZero();
        assertThat(service.seedProducts(-1)).isZero();
    }

    private static Product product(long id, String name) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setSku("SKU-" + id);
        product.setDescription(name + " description");
        return product;
    }
}
