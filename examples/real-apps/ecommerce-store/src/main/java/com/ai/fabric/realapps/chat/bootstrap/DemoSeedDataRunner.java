package com.ai.fabric.realapps.chat.bootstrap;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.repo.ProductRepository;
import com.ai.fabric.realapps.chat.promotions.domain.Coupon;
import com.ai.fabric.realapps.chat.promotions.repo.CouponRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo", name = "seed-data", havingValue = "true", matchIfMissing = true)
public class DemoSeedDataRunner implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final CouponRepository couponRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedProducts();
        seedCoupons();
    }

    private void seedProducts() {
        upsertProduct(
            "SKU-0001",
            "Premium Wireless Headphones",
            "Noise-cancelling over-ear headphones with 30h battery life. Great for travel and office use.",
            "Headphones",
            "wireless,noise-cancelling,audio",
            new BigDecimal("199.99"),
            "USD",
            25
        );

        upsertProduct(
            "SKU-0002",
            "Compact Mechanical Keyboard",
            "65% mechanical keyboard with hot-swappable switches and Bluetooth connectivity.",
            "Keyboards",
            "mechanical,compact,bluetooth",
            new BigDecimal("129.00"),
            "USD",
            40
        );
    }

    private void seedCoupons() {
        if (couponRepository.existsByCodeIgnoreCase("SAVE10")) {
            return;
        }

        Coupon coupon = new Coupon();
        coupon.setCode("SAVE10");
        coupon.setDescription("Save 10% on your order (demo)");
        coupon.setRules("Active for all users in the demo environment.");
        coupon.setActive(true);
        coupon.setDiscountPercent(10);
        couponRepository.save(coupon);
    }

    private void upsertProduct(String sku,
                               String name,
                               String description,
                               String category,
                               String tags,
                               BigDecimal price,
                               String currency,
                               int inStockQty) {
        if (productRepository.existsBySkuIgnoreCase(sku)) {
            return;
        }

        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setDescription(description);
        product.setCategory(category);
        product.setTags(tags);
        product.setPrice(price);
        product.setCurrency(currency);
        product.setInStockQty(inStockQty);
        productRepository.save(product);
    }
}
