package com.ai.fabric.realapps.chat.catalog.service;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.reviews.domain.Review;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductComparisonServiceTest {

    @Test
    void similarProductsPreferCategoryAndTagMatches() {
        ProductService productService = mock(ProductService.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        ProductComparisonService service = new ProductComparisonService(productService, reviewRepository);

        Product reference = product(1L, "SKU-0001", "Alpha Backpack", "Bags", "travel carry-on", "99.00", 4);
        Product similar = product(2L, "SKU-0002", "Beta Backpack", "Bags", "travel commuter", "109.00", 8);
        Product unrelated = product(3L, "SKU-0003", "Desk Lamp", "Lighting", "home office", "79.00", 12);

        when(productService.findBySku("sku-0001")).thenReturn(Optional.of(reference));
        when(productService.list(500)).thenReturn(List.of(reference, similar, unrelated));
        when(reviewRepository.findAll()).thenReturn(List.of(review("SKU-0001", 5), review("SKU-0002", 4)));

        var response = service.findSimilarProducts("SKU-0001", 5);

        assertThat(response.referenceProduct().sku()).isEqualTo("SKU-0001");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().getFirst().product().sku()).isEqualTo("SKU-0002");
        assertThat(response.items().getFirst().matchReasons()).isNotEmpty();
    }

    @Test
    void compareProductsReturnsDeterministicHighlights() {
        ProductService productService = mock(ProductService.class);
        ReviewRepository reviewRepository = mock(ReviewRepository.class);
        ProductComparisonService service = new ProductComparisonService(productService, reviewRepository);

        Product reference = product(1L, "SKU-0001", "Alpha Backpack", "Bags", "travel carry-on", "99.00", 4);
        Product comparison = product(2L, "SKU-0002", "Beta Backpack", "Bags", "travel commuter", "109.00", 8);

        when(productService.findBySku("sku-0001")).thenReturn(Optional.of(reference));
        when(productService.findBySku("sku-0002")).thenReturn(Optional.of(comparison));
        when(reviewRepository.findAll()).thenReturn(List.of(
            review("SKU-0001", 4),
            review("SKU-0001", 5),
            review("SKU-0002", 5),
            review("SKU-0002", 5)
        ));

        var response = service.compareProducts("SKU-0001", "SKU-0002");

        assertThat(response.sameCategory()).isTrue();
        assertThat(response.sharedTags()).contains("travel");
        assertThat(response.highlights().cheaperSku()).isEqualTo("SKU-0001");
        assertThat(response.highlights().higherRatedSku()).isEqualTo("SKU-0002");
        assertThat(response.keyDifferences()).isNotEmpty();
    }

    private Product product(Long id, String sku, String name, String category, String tags, String price, int stock) {
        Product product = new Product();
        product.setId(id);
        product.setSku(sku);
        product.setName(name);
        product.setDescription(name + " description");
        product.setCategory(category);
        product.setTags(tags);
        product.setPrice(new BigDecimal(price));
        product.setCurrency("USD");
        product.setInStockQty(stock);
        return product;
    }

    private Review review(String sku, int rating) {
        Review review = new Review();
        review.setSku(sku);
        review.setRating(rating);
        review.setText("review");
        review.setUserId("user-1");
        return review;
    }
}
