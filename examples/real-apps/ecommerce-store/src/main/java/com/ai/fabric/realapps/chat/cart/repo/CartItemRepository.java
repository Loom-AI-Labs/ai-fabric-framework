package com.ai.fabric.realapps.chat.cart.repo;

import com.ai.fabric.realapps.chat.cart.domain.CartItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByCartIdOrderByIdAsc(Long cartId);
}
