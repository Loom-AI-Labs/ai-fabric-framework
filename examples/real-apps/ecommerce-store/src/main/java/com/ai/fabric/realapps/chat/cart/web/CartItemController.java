package com.ai.fabric.realapps.chat.cart.web;

import com.ai.fabric.realapps.chat.cart.domain.CartItem;
import com.ai.fabric.realapps.chat.cart.service.CartItemService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cart-items")
@RequiredArgsConstructor
public class CartItemController {

    private final CartItemService cartItemService;

    @GetMapping
    public List<CartItem> listForCart(@RequestParam("cartId") long cartId) {
        return cartItemService.listForCart(cartId);
    }

    @GetMapping("/{id}")
    public CartItem get(@PathVariable long id) {
        return cartItemService.get(id);
    }

    @DeleteMapping("/{id}")
    public CartItem delete(@PathVariable long id) {
        return cartItemService.delete(id);
    }
}
