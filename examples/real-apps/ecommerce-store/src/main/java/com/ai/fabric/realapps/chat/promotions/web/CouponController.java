package com.ai.fabric.realapps.chat.promotions.web;

import com.ai.fabric.realapps.chat.promotions.domain.Coupon;
import com.ai.fabric.realapps.chat.promotions.service.CouponService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
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
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @GetMapping
    public List<Coupon> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return couponService.list(limit);
    }

    @GetMapping("/{id}")
    public Coupon get(@PathVariable long id) {
        return couponService.get(id);
    }

    @GetMapping("/by-code")
    public ResponseEntity<Coupon> getByCode(@RequestParam("code") String code) {
        return couponService.findByCode(code).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Coupon> create(@Valid @RequestBody CreateCouponRequest request) {
        Coupon created = couponService.create(
            request.getCode(),
            request.getDescription(),
            request.getRules(),
            request.isActive(),
            request.getDiscountPercent(),
            request.getDiscountAmount()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public Coupon update(@PathVariable long id, @Valid @RequestBody UpdateCouponRequest request) {
        return couponService.update(
            id,
            request.getDescription(),
            request.getRules(),
            request.getActive(),
            request.getDiscountPercent(),
            request.getDiscountAmount()
        );
    }

    @DeleteMapping("/{id}")
    public Coupon delete(@PathVariable long id) {
        return couponService.delete(id);
    }

    @Data
    public static class CreateCouponRequest {
        @NotBlank
        private String code;
        private String description;
        private String rules;
        private boolean active = true;
        private Integer discountPercent;
        private BigDecimal discountAmount;
    }

    @Data
    public static class UpdateCouponRequest {
        private String description;
        private String rules;
        private Boolean active;
        private Integer discountPercent;
        private BigDecimal discountAmount;
    }
}
