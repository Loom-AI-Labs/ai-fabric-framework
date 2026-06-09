package com.ai.fabric.realapps.chat.payments.service;

import com.ai.fabric.realapps.chat.payments.domain.Payment;
import com.ai.fabric.realapps.chat.payments.repo.PaymentRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public Payment get(long id) {
        return paymentRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Payment not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Payment> listForUser(String userId, int limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 200);
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId.trim()).stream()
            .limit(effectiveLimit)
            .toList();
    }

    @Transactional
    public Payment delete(long id) {
        Payment payment = get(id);
        paymentRepository.delete(payment);
        return payment;
    }
}
