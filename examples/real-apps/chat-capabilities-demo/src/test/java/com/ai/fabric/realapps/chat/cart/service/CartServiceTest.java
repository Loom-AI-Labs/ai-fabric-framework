package com.ai.fabric.realapps.chat.cart.service;

import com.ai.fabric.realapps.chat.cart.domain.Cart;
import com.ai.fabric.realapps.chat.cart.domain.CartItem;
import com.ai.fabric.realapps.chat.cart.repo.CartRepository;
import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import com.ai.fabric.realapps.chat.payments.repo.PaymentRepository;
import com.ai.fabric.realapps.chat.promotions.service.CouponService;
import com.ai.fabric.realapps.chat.shipping.repo.ShipmentRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CartServiceTest {

    private final CartRepository cartRepository = mock(CartRepository.class);
    private final ProductService productService = mock(ProductService.class);
    private final CouponService couponService = mock(CouponService.class);
    private final PurchaseOrderService purchaseOrderService = mock(PurchaseOrderService.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
    private final CartService service = new CartService(
        cartRepository,
        productService,
        couponService,
        purchaseOrderService,
        paymentRepository,
        shipmentRepository
    );

    @Test
    void addItemAcceptsExactProductTitleAndStoresCanonicalSku() {
        when(cartRepository.findFirstByUserIdAndStatusOrderByUpdatedAtDesc("user-1", Cart.Status.ACTIVE))
            .thenReturn(Optional.empty());
        when(productService.findBySkuOrName("Alienware M18 R2 Gaming Laptop"))
            .thenReturn(Optional.of(product()));
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Cart cart = service.addItem("user-1", "Alienware M18 R2 Gaming Laptop", 2);

        assertThat(cart.getItems()).hasSize(1);
        CartItem item = cart.getItems().getFirst();
        assertThat(item.getSku()).isEqualTo("SKU-LAP-9001");
        assertThat(item.getProductName()).isEqualTo("Alienware M18 R2 Gaming Laptop");
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getTotalPrice()).isEqualByComparingTo("4998.00");
        assertThat(cart.getTotal()).isEqualByComparingTo("4998.00");
    }

    @Test
    void addItemByTitleIncrementsExistingCanonicalSkuRow() {
        Cart existingCart = activeCart("user-1");
        CartItem existingItem = new CartItem();
        existingItem.setCart(existingCart);
        existingItem.setSku("SKU-LAP-9001");
        existingItem.setProductId(1L);
        existingItem.setProductName("Alienware M18 R2 Gaming Laptop");
        existingItem.setQuantity(1);
        existingItem.setUnitPrice(new BigDecimal("2499.00"));
        existingItem.setTotalPrice(new BigDecimal("2499.00"));
        existingCart.getItems().add(existingItem);

        when(cartRepository.findFirstByUserIdAndStatusOrderByUpdatedAtDesc("user-1", Cart.Status.ACTIVE))
            .thenReturn(Optional.of(existingCart));
        when(productService.findBySkuOrName("Alienware M18 R2 Gaming Laptop"))
            .thenReturn(Optional.of(product()));
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Cart cart = service.addItem("user-1", "Alienware M18 R2 Gaming Laptop", 1);

        assertThat(cart.getItems()).hasSize(1);
        CartItem item = cart.getItems().getFirst();
        assertThat(item.getSku()).isEqualTo("SKU-LAP-9001");
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getTotalPrice()).isEqualByComparingTo("4998.00");
        assertThat(cart.getTotal()).isEqualByComparingTo("4998.00");
    }

    private static Cart activeCart(String userId) {
        Cart cart = new Cart();
        cart.setUserId(userId);
        cart.setStatus(Cart.Status.ACTIVE);
        cart.setCurrency("USD");
        return cart;
    }

    private static Product product() {
        Product product = new Product();
        product.setId(1L);
        product.setSku("SKU-LAP-9001");
        product.setName("Alienware M18 R2 Gaming Laptop");
        product.setPrice(new BigDecimal("2499.00"));
        product.setCurrency("USD");
        product.setInStockQty(17);
        return product;
    }
}
