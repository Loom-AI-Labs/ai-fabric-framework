package com.ai.fabric.realapps.chat.reviews.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.ai.fabric.realapps.chat.reviews.domain.Review;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewServiceSearchTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final ReviewService service = new ReviewService(reviewRepository, aiCoreService);

    @Test
    void searchHydratesValidResultIdsAndSkipsMalformedRows() {
        Review first = review(1L);
        Review second = review(2L);
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(
                Map.of("entityId", "1"),
                Map.of("id", "bad"),
                Map.of(),
                Map.of("id", 2L)
            ))
            .build());
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(first));
        when(reviewRepository.findById(2L)).thenReturn(Optional.of(second));

        List<Review> results = service.search("great", 10, 0.2d);

        assertThat(results).extracting(Review::getId).containsExactly(1L, 2L);
    }

    private static Review review(long id) {
        Review review = new Review();
        review.setId(id);
        review.setUserId("user-" + id);
        review.setRating(5);
        review.setText("Great product");
        return review;
    }
}
