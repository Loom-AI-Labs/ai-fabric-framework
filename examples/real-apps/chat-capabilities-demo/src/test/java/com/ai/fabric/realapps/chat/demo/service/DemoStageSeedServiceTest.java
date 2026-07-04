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
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoStageSeedServiceTest {

    private final ProductService productService = mock(ProductService.class);
    private final PolicyService policyService = mock(PolicyService.class);
    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final ReviewService reviewService = mock(ReviewService.class);
    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final CouponService couponService = mock(CouponService.class);
    private final SupportTicketService supportTicketService = mock(SupportTicketService.class);
    private final SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
    private final DemoReadinessService readinessService = mock(DemoReadinessService.class);

    private final DemoStageSeedService seedService = new DemoStageSeedService(
        productService,
        policyService,
        policyRepository,
        reviewService,
        reviewRepository,
        couponService,
        supportTicketService,
        supportTicketRepository,
        readinessService
    );

    @Test
    void fullSeedSkipsUnchangedRowsWithoutUpdatingOrDoubleSeedingProducts() {
        Map<String, Product> products = DemoSeedCatalog.products().stream()
            .map(this::productFromSeed)
            .collect(Collectors.toMap(Product::getSku, Function.identity()));
        Map<String, Policy> policies = DemoSeedCatalog.policies().stream()
            .map(this::policyFromSeed)
            .collect(Collectors.toMap(Policy::getTitle, Function.identity()));
        Map<String, Coupon> coupons = DemoSeedCatalog.coupons().stream()
            .map(this::couponFromSeed)
            .collect(Collectors.toMap(Coupon::getCode, Function.identity()));

        when(productService.findBySku(anyString())).thenAnswer(invocation ->
            Optional.ofNullable(products.get(invocation.getArgument(0, String.class))));
        when(policyRepository.findByTitleIgnoreCase(anyString())).thenAnswer(invocation ->
            Optional.ofNullable(policies.get(invocation.getArgument(0, String.class))));
        when(couponService.findByCode(anyString())).thenAnswer(invocation ->
            Optional.ofNullable(coupons.get(invocation.getArgument(0, String.class))));
        when(reviewRepository.existsByUserIdAndSkuIgnoreCaseAndText(anyString(), anyString(), anyString()))
            .thenReturn(true);
        when(supportTicketRepository.existsByUserIdAndIssueTypeIgnoreCaseAndDescription(anyString(), anyString(), anyString()))
            .thenReturn(true);

        DemoStageSeedService.StageSeedResult result = seedService.seed(DemoStage.FULL);

        assertCounts(result, "products", 0, 0, DemoSeedCatalog.products().size(), DemoSeedCatalog.products().size());
        assertSkippedOnly(result, "reviews", DemoSeedCatalog.reviews(DemoSeedCatalog.products()).size());
        assertCounts(result, "policies", 0, 0, DemoSeedCatalog.policies().size(), DemoSeedCatalog.policies().size());
        assertCounts(result, "coupons", 0, 0, DemoSeedCatalog.coupons().size(), DemoSeedCatalog.coupons().size());
        assertSkippedOnly(result, "tickets", DemoSeedCatalog.tickets().size());

        verify(productService, times(DemoSeedCatalog.products().size())).findBySku(anyString());
        verify(productService, never()).updateProduct(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(productService, never()).createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(policyService, never()).updatePolicy(anyLong(), any(), any(), any());
        verify(policyService, never()).createPolicy(any(), any(), any());
        verify(couponService, never()).update(anyLong(), any(), any(), any(), any(), any());
        verify(couponService, never()).create(any(), any(), any(), any(Boolean.class), any(), any());
        verify(reviewService, never()).create(any(), any(), any(), anyInt(), any());
        verify(supportTicketService, never()).create(any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private void assertCounts(DemoStageSeedService.StageSeedResult result,
                              String key,
                              int created,
                              int updated,
                              int skipped,
                              int target) {
        Map<String, Object> counts = (Map<String, Object>) result.getResults().get(key);
        assertEquals(created, counts.get("created"));
        assertEquals(updated, counts.get("updated"));
        assertEquals(skipped, counts.get("skipped"));
        assertEquals(target, counts.get("target"));
    }

    @SuppressWarnings("unchecked")
    private void assertSkippedOnly(DemoStageSeedService.StageSeedResult result, String key, int skipped) {
        Map<String, Object> counts = (Map<String, Object>) result.getResults().get(key);
        assertEquals(0, counts.get("created"));
        assertEquals(skipped, counts.get("skipped"));
        assertEquals(skipped, counts.get("target"));
    }

    private Product productFromSeed(DemoSeedCatalog.ProductSeed seed) {
        Product product = new Product();
        product.setId((long) seed.sku().hashCode());
        product.setSku(seed.sku());
        product.setName(seed.name());
        product.setDescription(seed.description());
        product.setCategory(seed.category());
        product.setTags(seed.tags());
        product.setImageUrl(seed.imageUrl());
        product.setPrice(new BigDecimal(seed.price()));
        product.setCurrency("USD");
        product.setInStockQty(seed.stock());
        return product;
    }

    private Policy policyFromSeed(DemoSeedCatalog.PolicySeed seed) {
        Policy policy = new Policy();
        policy.setId((long) seed.title().hashCode());
        policy.setTitle(seed.title());
        policy.setText(seed.text());
        policy.setClassification(seed.classification());
        return policy;
    }

    private Coupon couponFromSeed(DemoSeedCatalog.CouponSeed seed) {
        Coupon coupon = new Coupon();
        coupon.setId((long) seed.code().hashCode());
        coupon.setCode(seed.code());
        coupon.setDescription(seed.description());
        coupon.setRules(seed.rules());
        coupon.setActive(seed.active());
        coupon.setDiscountPercent(seed.discountPercent());
        coupon.setDiscountAmount(seed.discountAmount());
        return coupon;
    }
}
