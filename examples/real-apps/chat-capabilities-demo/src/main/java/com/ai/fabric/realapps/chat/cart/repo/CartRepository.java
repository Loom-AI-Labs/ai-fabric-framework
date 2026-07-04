package com.ai.fabric.realapps.chat.cart.repo;

import com.ai.fabric.realapps.chat.cart.domain.Cart;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface CartRepository extends JpaRepository<Cart, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<Cart> findFirstByUserIdAndStatusOrderByUpdatedAtDesc(String userId, Cart.Status status);

    @EntityGraph(attributePaths = "items")
    Optional<Cart> findById(Long id);

    List<Cart> findByUserIdOrderByUpdatedAtDesc(String userId);

    List<Cart> findByUserIdStartingWithAndUpdatedAtBefore(String userIdPrefix, LocalDateTime cutoff);
}
