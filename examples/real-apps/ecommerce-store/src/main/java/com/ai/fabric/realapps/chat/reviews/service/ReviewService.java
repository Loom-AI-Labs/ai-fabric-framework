package com.ai.fabric.realapps.chat.reviews.service;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.repo.ProductRepository;
import com.ai.fabric.realapps.chat.indexing.events.ReviewDeleteIndexingEvent;
import com.ai.fabric.realapps.chat.indexing.events.ReviewUpsertIndexingEvent;
import com.ai.fabric.realapps.chat.reviews.domain.Review;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

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
        String resolvedSku = resolveSku(productId, sku);
        review.setSku(resolvedSku);
        review.setRating(rating);
        review.setText(text.trim());
        Review saved = reviewRepository.save(review);
        publishUpsert(saved);
        return saved;
    }

    @Transactional
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

        Review saved = reviewRepository.save(review);
        publishUpsert(saved);
        return saved;
    }

    @Transactional
    public Review delete(long id) {
        Review review = get(id);
        reviewRepository.delete(review);
        eventPublisher.publishEvent(new ReviewDeleteIndexingEvent(id));
        return review;
    }

    @Transactional(readOnly = true)
    public List<Review> search(String query, int limit, double threshold) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        int effectiveLimit = limit <= 0 ? 10 : Math.min(limit, 50);
        String normalized = query.trim().toLowerCase();

        return reviewRepository.findAll().stream()
            .filter(r -> matches(r, normalized))
            .sorted(Comparator.comparing(Review::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Review::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(effectiveLimit)
            .toList();
    }

    private boolean matches(Review review, String normalizedLowerQuery) {
        if (review == null || !StringUtils.hasText(normalizedLowerQuery)) {
            return false;
        }
        return containsIgnoreCase(review.getText(), normalizedLowerQuery)
            || containsIgnoreCase(review.getSku(), normalizedLowerQuery)
            || containsIgnoreCase(review.getUserId(), normalizedLowerQuery);
    }

    private boolean containsIgnoreCase(String haystack, String needleLower) {
        if (!StringUtils.hasText(haystack) || !StringUtils.hasText(needleLower)) {
            return false;
        }
        return haystack.toLowerCase().contains(needleLower);
    }

    private String resolveSku(Long productId, String providedSku) {
        if (StringUtils.hasText(providedSku)) {
            return providedSku.trim();
        }
        if (productId == null) {
            return null;
        }
        Product product = productRepository.findById(productId).orElse(null);
        return product != null && StringUtils.hasText(product.getSku()) ? product.getSku().trim() : null;
    }

    private void publishUpsert(Review saved) {
        if (saved == null || saved.getId() == null) {
            return;
        }
        eventPublisher.publishEvent(new ReviewUpsertIndexingEvent(
            saved.getId(),
            saved.getUserId(),
            saved.getSku(),
            saved.getRating(),
            saved.getText()
        ));
    }
}
