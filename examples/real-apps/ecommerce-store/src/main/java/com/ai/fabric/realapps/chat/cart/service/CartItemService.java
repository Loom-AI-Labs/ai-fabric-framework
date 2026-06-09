package com.ai.fabric.realapps.chat.cart.service;

import com.ai.fabric.realapps.chat.cart.domain.CartItem;
import com.ai.fabric.realapps.chat.cart.repo.CartItemRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CartItemService {

    private final CartItemRepository cartItemRepository;

    @Transactional(readOnly = true)
    public CartItem get(long id) {
        return cartItemRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("CartItem not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<CartItem> listForCart(long cartId) {
        return cartItemRepository.findByCartIdOrderByIdAsc(cartId);
    }

    @Transactional
    public CartItem delete(long id) {
        CartItem item = get(id);
        cartItemRepository.delete(item);
        return item;
    }
}
