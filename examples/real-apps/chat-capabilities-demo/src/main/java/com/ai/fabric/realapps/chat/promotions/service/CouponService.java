package com.ai.fabric.realapps.chat.promotions.service;

import com.ai.fabric.realapps.chat.promotions.domain.Coupon;
import com.ai.fabric.realapps.chat.promotions.repo.CouponRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;

    @Transactional(readOnly = true)
    public List<Coupon> list(int limit) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return couponRepository.findAll().stream().limit(effectiveLimit).toList();
    }

    @Transactional(readOnly = true)
    public Coupon get(long id) {
        return couponRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Coupon not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<Coupon> findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return Optional.empty();
        }
        return couponRepository.findByCodeIgnoreCase(code.trim());
    }

    @Transactional
    public Coupon create(String code,
                         String description,
                         String rules,
                         boolean active,
                         Integer discountPercent,
                         BigDecimal discountAmount) {
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("code is required");
        }
        String normalizedCode = code.trim().toUpperCase();
        if (couponRepository.existsByCodeIgnoreCase(normalizedCode)) {
            throw new IllegalArgumentException("coupon code already exists: " + normalizedCode);
        }

        Coupon coupon = new Coupon();
        coupon.setCode(normalizedCode);
        coupon.setDescription(StringUtils.hasText(description) ? description.trim() : null);
        coupon.setRules(StringUtils.hasText(rules) ? rules.trim() : null);
        coupon.setActive(active);
        coupon.setDiscountPercent(discountPercent);
        coupon.setDiscountAmount(discountAmount);
        return couponRepository.save(coupon);
    }

    @Transactional
    public Coupon update(long id,
                         String description,
                         String rules,
                         Boolean active,
                         Integer discountPercent,
                         BigDecimal discountAmount) {
        Coupon coupon = get(id);

        if (description != null) {
            coupon.setDescription(StringUtils.hasText(description) ? description.trim() : null);
        }
        if (rules != null) {
            coupon.setRules(StringUtils.hasText(rules) ? rules.trim() : null);
        }
        if (active != null) {
            coupon.setActive(active);
        }
        if (discountPercent != null) {
            coupon.setDiscountPercent(discountPercent);
        }
        if (discountAmount != null) {
            coupon.setDiscountAmount(discountAmount);
        }

        return couponRepository.save(coupon);
    }

    @Transactional
    public Coupon delete(long id) {
        Coupon coupon = get(id);
        couponRepository.delete(coupon);
        return coupon;
    }
}
