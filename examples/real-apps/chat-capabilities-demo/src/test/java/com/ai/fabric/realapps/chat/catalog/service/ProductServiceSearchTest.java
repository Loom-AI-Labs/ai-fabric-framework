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
import static org.mockito.Mockito.verify;
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

    @Test
    void findBySkuOrNameUsesSkuLookupFirst() {
        Product product = product(1L, "SKU-LAP-9001");
        when(productRepository.findBySku("sku-lap-9001")).thenReturn(Optional.of(product));

        assertThat(service.findBySkuOrName(" SKU-LAP-9001 ")).containsSame(product);
    }

    @Test
    void findBySkuOrNameFallsBackToExactProductTitleWithoutRemovingSpaces() {
        Product product = product(1L, "SKU-LAP-9001");
        product.setName("Alienware M18 R2 Gaming Laptop");
        when(productRepository.findBySku("alienwarem18r2gaminglaptop")).thenReturn(Optional.empty());
        when(productRepository.findBySkuIgnoreCase("alienwarem18r2gaminglaptop")).thenReturn(Optional.empty());
        when(productRepository.findFirstByNameIgnoreCaseOrderByIdAsc("Alienware M18 R2 Gaming Laptop"))
            .thenReturn(Optional.of(product));

        assertThat(service.findBySkuOrName(" Alienware M18 R2 Gaming Laptop ")).containsSame(product);
        verify(productRepository).findFirstByNameIgnoreCaseOrderByIdAsc("Alienware M18 R2 Gaming Laptop");
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
