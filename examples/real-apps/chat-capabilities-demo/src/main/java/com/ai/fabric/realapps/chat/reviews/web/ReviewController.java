package com.ai.fabric.realapps.chat.reviews.web;

import com.ai.fabric.realapps.chat.reviews.domain.Review;
import com.ai.fabric.realapps.chat.reviews.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public List<Review> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return reviewService.list(limit);
    }

    @GetMapping("/{id}")
    public Review get(@PathVariable long id) {
        return reviewService.get(id);
    }

    @GetMapping("/by-product-id/{productId}")
    public List<Review> listForProductId(@PathVariable long productId,
                                         @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return reviewService.listForProductId(productId, limit);
    }

    @GetMapping("/by-sku")
    public List<Review> listForSku(@RequestParam("sku") String sku,
                                   @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return reviewService.listForSku(sku, limit);
    }

    @GetMapping("/search")
    public List<Review> search(@RequestParam("q") String query,
                               @RequestParam(value = "limit", defaultValue = "10") int limit,
                               @RequestParam(value = "threshold", defaultValue = "0.3") double threshold) {
        return reviewService.search(query, limit, threshold);
    }

    @PostMapping
    public ResponseEntity<Review> create(@Valid @RequestBody CreateReviewRequest request) {
        Review created = reviewService.create(
            request.getUserId(),
            request.getProductId(),
            request.getSku(),
            request.getRating(),
            request.getText()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public Review update(@PathVariable long id, @Valid @RequestBody UpdateReviewRequest request) {
        return reviewService.update(id, request.getRating(), request.getText());
    }

    @DeleteMapping("/{id}")
    public Review delete(@PathVariable long id) {
        return reviewService.delete(id);
    }

    @Data
    public static class CreateReviewRequest {
        @NotBlank
        private String userId;
        private Long productId;
        private String sku;
        @Min(1)
        @Max(5)
        private int rating;
        @NotBlank
        private String text;
    }

    @Data
    public static class UpdateReviewRequest {
        @Min(1)
        @Max(5)
        private Integer rating;
        private String text;
    }
}
