package com.ai.fabric.realapps.chat.returns.service;

import com.ai.fabric.realapps.chat.orders.domain.PurchaseOrder;
import com.ai.fabric.realapps.chat.orders.service.PurchaseOrderService;
import com.ai.fabric.realapps.chat.returns.domain.ReturnRequest;
import com.ai.fabric.realapps.chat.returns.repo.ReturnRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ReturnRequestService {

    private final ReturnRequestRepository returnRequestRepository;
    private final PurchaseOrderService purchaseOrderService;

    @Transactional(readOnly = true)
    public ReturnRequest get(long id) {
        return returnRequestRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Return request not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<ReturnRequest> listForUser(String userId, int limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 200);
        return returnRequestRepository.findByUserIdOrderByCreatedAtDesc(userId.trim()).stream()
            .limit(effectiveLimit)
            .toList();
    }

    @Transactional
    public ReturnRequest create(String userId, String orderNumberOrId, String reason) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        if (!StringUtils.hasText(orderNumberOrId)) {
            throw new IllegalArgumentException("orderNumber is required");
        }
        if (!StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("reason is required");
        }

        PurchaseOrder order = purchaseOrderService.resolveForUser(orderNumberOrId, userId);

        boolean eligible = isEligible(order.getCreatedAt());
        ReturnRequest request = new ReturnRequest();
        request.setOrder(order);
        request.setUserId(userId.trim());
        request.setReason(reason.trim());
        request.setEligible(eligible);
        request.setStatus(eligible ? ReturnRequest.Status.REQUESTED : ReturnRequest.Status.REJECTED);
        return returnRequestRepository.save(request);
    }

    @Transactional
    public ReturnRequest updateStatus(long id, ReturnRequest.Status status, Boolean eligible) {
        ReturnRequest request = get(id);
        if (eligible != null) {
            request.setEligible(eligible);
        }
        if (status != null) {
            request.setStatus(status);
        }
        return returnRequestRepository.save(request);
    }

    @Transactional
    public ReturnRequest delete(long id) {
        ReturnRequest request = get(id);
        returnRequestRepository.delete(request);
        return request;
    }

    private boolean isEligible(LocalDateTime orderCreatedAt) {
        if (orderCreatedAt == null) {
            return false;
        }
        Duration age = Duration.between(orderCreatedAt, LocalDateTime.now());
        return age.toDays() <= 30;
    }
}
