package com.ai.fabric.realapps.chat.reviews.service;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.repo.ProductRepository;
import com.ai.fabric.realapps.chat.reviews.domain.Review;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ReviewService service = new ReviewService(reviewRepository, productRepository, eventPublisher);

    @Test
    void createResolvesSkuFromProductWhenSkuIsNotProvided() {
        Product product = new Product();
        product.setId(10L);
        product.setSku("SKU-0010");
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            review.setId(99L);
            return review;
        });

        Review saved = service.create("user-1", 10L, null, 5, "Great");

        assertThat(saved.getSku()).isEqualTo("SKU-0010");
    }

    @Test
    void createAllowsReviewWithoutProductReference() {
        when(reviewRepository.save(any(Review.class))).thenAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            review.setId(100L);
            return review;
        });

        Review saved = service.create("user-1", null, null, 4, "Useful");

        assertThat(saved.getSku()).isNull();
    }
}
