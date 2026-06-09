package com.ai.fabric.realapps.chat.reviews.repo;

import com.ai.fabric.realapps.chat.reviews.domain.Review;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByProductIdOrderByCreatedAtDesc(Long productId);

    List<Review> findBySkuIgnoreCaseOrderByCreatedAtDesc(String sku);
}
