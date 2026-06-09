package com.ai.fabric.realapps.chat.reviews.service;

import com.ai.fabric.realapps.chat.reviews.domain.Review;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import com.ai.infrastructure.annotation.AIProcess;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final AICoreService aiCoreService;

    @Transactional(readOnly = true)
    public List<Review> list(int limit) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return reviewRepository.findAll().stream().limit(effectiveLimit).toList();
    }

    @Transactional(readOnly = true)
    public Review get(long id) {
        return reviewRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Review not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Review> listForProductId(long productId, int limit) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return reviewRepository.findByProductIdOrderByCreatedAtDesc(productId).stream().limit(effectiveLimit).toList();
    }

    @Transactional(readOnly = true)
    public List<Review> listForSku(String sku, int limit) {
        if (!StringUtils.hasText(sku)) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return reviewRepository.findBySkuIgnoreCaseOrderByCreatedAtDesc(sku.trim()).stream().limit(effectiveLimit).toList();
    }

    @Transactional
    @AIProcess(entityType = "review", processType = "create", generateEmbedding = true, indexForSearch = true)
    public Review create(String userId, Long productId, String sku, int rating, String text) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("text is required");
        }

        Review review = new Review();
        review.setUserId(userId.trim());
        review.setProductId(productId);
        review.setSku(StringUtils.hasText(sku) ? sku.trim() : null);
        review.setRating(rating);
        review.setText(text.trim());
        return reviewRepository.save(review);
    }

    @Transactional
    @AIProcess(entityType = "review", processType = "update", generateEmbedding = true, indexForSearch = true)
    public Review update(long id, Integer rating, String text) {
        Review review = get(id);

        if (rating != null) {
            if (rating < 1 || rating > 5) {
                throw new IllegalArgumentException("rating must be between 1 and 5");
            }
            review.setRating(rating);
        }
        if (text != null) {
            review.setText(StringUtils.hasText(text) ? text.trim() : review.getText());
        }

        return reviewRepository.save(review);
    }

    @Transactional
    @AIProcess(entityType = "review", processType = "delete", generateEmbedding = false, indexForSearch = false)
    public Review delete(long id) {
        Review review = get(id);
        reviewRepository.delete(review);
        return review;
    }

    @Transactional(readOnly = true)
    public List<Review> search(String query, int limit, double threshold) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        int effectiveLimit = limit <= 0 ? 10 : Math.min(limit, 50);
        double effectiveThreshold = threshold <= 0.0 ? 0.3 : threshold;

        AISearchResponse response = aiCoreService.performSearch(AISearchRequest.builder()
            .query(query)
            .entityType("review")
            .limit(effectiveLimit)
            .threshold(effectiveThreshold)
            .build());

        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return List.of();
        }

        return response.getResults().stream()
            .map(this::extractEntityId)
            .filter(Objects::nonNull)
            .map(id -> {
                try {
                    return reviewRepository.findById(Long.parseLong(id)).orElse(null);
                } catch (NumberFormatException ex) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .limit(effectiveLimit)
            .toList();
    }

    private String extractEntityId(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object id = firstNonNull(row.get("entityId"), row.get("id"));
        return id != null ? Objects.toString(id, null) : null;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }
}
