package com.ai.fabric.realapps.chat.demo.service;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import com.ai.fabric.realapps.chat.policies.domain.Policy;
import com.ai.fabric.realapps.chat.policies.repo.PolicyRepository;
import com.ai.fabric.realapps.chat.policies.service.PolicyService;
import com.ai.fabric.realapps.chat.promotions.domain.Coupon;
import com.ai.fabric.realapps.chat.promotions.service.CouponService;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import com.ai.fabric.realapps.chat.reviews.service.ReviewService;
import com.ai.fabric.realapps.chat.support.repo.SupportTicketRepository;
import com.ai.fabric.realapps.chat.support.service.SupportTicketService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class DemoStageSeedService {

    private final ProductService productService;
    private final PolicyService policyService;
    private final PolicyRepository policyRepository;
    private final ReviewService reviewService;
    private final ReviewRepository reviewRepository;
    private final CouponService couponService;
    private final SupportTicketService supportTicketService;
    private final SupportTicketRepository supportTicketRepository;
    private final DemoReadinessService readinessService;

    @Transactional
    public StageSeedResult seed(DemoStage stage) {
        DemoStage effectiveStage = stage != null ? stage : DemoStage.FULL;
        Map<String, Object> results = new LinkedHashMap<>();

        if (effectiveStage == DemoStage.PRODUCTS || effectiveStage == DemoStage.FULL) {
            results.put("products", seedProducts());
        }
        if (effectiveStage == DemoStage.REVIEWS || effectiveStage == DemoStage.FULL) {
            results.put("products", seedProducts());
            results.put("reviews", seedReviews());
        }
        if (effectiveStage == DemoStage.POLICIES || effectiveStage == DemoStage.FULL) {
            results.put("policies", seedPolicies());
        }
        if (effectiveStage == DemoStage.COUPONS || effectiveStage == DemoStage.FULL) {
            results.put("coupons", seedCoupons());
        }
        if (effectiveStage == DemoStage.TICKETS || effectiveStage == DemoStage.FULL) {
            results.put("tickets", seedTickets());
        }

        return StageSeedResult.builder()
            .success(true)
            .stage(effectiveStage.name().toLowerCase())
            .results(results)
            .readiness(readinessService.readiness())
            .build();
    }

    private Map<String, Object> seedProducts() {
        int created = 0;
        int updated = 0;
        for (DemoSeedCatalog.ProductSeed seed : DemoSeedCatalog.products()) {
            if (productService.findBySku(seed.sku()).isPresent()) {
                Product existing = productService.findBySku(seed.sku()).orElseThrow();
                productService.updateProduct(
                    existing.getId(),
                    seed.sku(),
                    seed.name(),
                    seed.description(),
                    seed.category(),
                    seed.tags(),
                    seed.imageUrl(),
                    new BigDecimal(seed.price()),
                    "USD",
                    seed.stock()
                );
                updated++;
            } else {
                productService.createProduct(
                    seed.sku(),
                    seed.name(),
                    seed.description(),
                    seed.category(),
                    seed.tags(),
                    seed.imageUrl(),
                    new BigDecimal(seed.price()),
                    "USD",
                    seed.stock()
                );
                created++;
            }
        }
        return Map.of("created", created, "updated", updated, "target", DemoSeedCatalog.products().size());
    }

    private Map<String, Object> seedPolicies() {
        int created = 0;
        int updated = 0;
        for (DemoSeedCatalog.PolicySeed seed : DemoSeedCatalog.policies()) {
            Policy existing = policyRepository.findByTitleIgnoreCase(seed.title()).orElse(null);
            if (existing == null) {
                policyService.createPolicy(seed.title(), seed.text(), seed.classification());
                created++;
            } else {
                policyService.updatePolicy(existing.getId(), seed.title(), seed.text(), seed.classification());
                updated++;
            }
        }
        return Map.of("created", created, "updated", updated, "target", DemoSeedCatalog.policies().size());
    }

    private Map<String, Object> seedReviews() {
        int created = 0;
        int skipped = 0;
        List<DemoSeedCatalog.ProductSeed> products = DemoSeedCatalog.products();
        for (DemoSeedCatalog.ReviewSeed seed : DemoSeedCatalog.reviews(products)) {
            if (reviewRepository.existsByUserIdAndSkuIgnoreCaseAndText(seed.userId(), seed.sku(), seed.text())) {
                skipped++;
                continue;
            }
            Product product = productService.findBySku(seed.sku()).orElse(null);
            reviewService.create(seed.userId(), product != null ? product.getId() : null, seed.sku(), seed.rating(), seed.text());
            created++;
        }
        return Map.of("created", created, "skipped", skipped, "target", DemoSeedCatalog.reviews(products).size());
    }

    private Map<String, Object> seedCoupons() {
        int created = 0;
        int updated = 0;
        for (DemoSeedCatalog.CouponSeed seed : DemoSeedCatalog.coupons()) {
            Coupon existing = couponService.findByCode(seed.code()).orElse(null);
            if (existing == null) {
                couponService.create(
                    seed.code(),
                    seed.description(),
                    seed.rules(),
                    seed.active(),
                    seed.discountPercent(),
                    seed.discountAmount()
                );
                created++;
            } else {
                couponService.update(
                    existing.getId(),
                    seed.description(),
                    seed.rules(),
                    seed.active(),
                    seed.discountPercent(),
                    seed.discountAmount()
                );
                updated++;
            }
        }
        return Map.of("created", created, "updated", updated, "target", DemoSeedCatalog.coupons().size());
    }

    private Map<String, Object> seedTickets() {
        int created = 0;
        int skipped = 0;
        for (DemoSeedCatalog.TicketSeed seed : DemoSeedCatalog.tickets()) {
            if (!StringUtils.hasText(seed.userId()) || supportTicketRepository.existsByUserIdAndIssueTypeIgnoreCaseAndDescription(
                seed.userId(),
                seed.issueType(),
                seed.description()
            )) {
                skipped++;
                continue;
            }
            supportTicketService.create(seed.userId(), seed.issueType(), seed.description(), seed.orderNumber());
            created++;
        }
        return Map.of("created", created, "skipped", skipped, "target", DemoSeedCatalog.tickets().size());
    }

    @Data
    @Builder
    public static class StageSeedResult {
        private boolean success;
        private String stage;
        private Map<String, Object> results;
        private DemoReadinessService.ReadinessReport readiness;
    }
}
