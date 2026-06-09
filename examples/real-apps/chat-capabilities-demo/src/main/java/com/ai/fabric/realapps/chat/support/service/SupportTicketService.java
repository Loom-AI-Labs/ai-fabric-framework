package com.ai.fabric.realapps.chat.support.service;

import com.ai.fabric.realapps.chat.support.domain.SupportTicket;
import com.ai.fabric.realapps.chat.support.repo.SupportTicketRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SupportTicketService {

    private final SupportTicketRepository supportTicketRepository;

    @Transactional(readOnly = true)
    public SupportTicket get(long id) {
        return supportTicketRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Support ticket not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<SupportTicket> listForUser(String userId, int limit) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 200);
        return supportTicketRepository.findByUserIdOrderByCreatedAtDesc(userId.trim()).stream()
            .limit(effectiveLimit)
            .toList();
    }

    @Transactional
    public SupportTicket create(String userId, String issueType, String description, String orderNumber) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required");
        }
        if (!StringUtils.hasText(issueType)) {
            throw new IllegalArgumentException("issueType is required");
        }
        if (!StringUtils.hasText(description)) {
            throw new IllegalArgumentException("description is required");
        }

        SupportTicket ticket = new SupportTicket();
        ticket.setUserId(userId.trim());
        ticket.setIssueType(issueType.trim());
        ticket.setDescription(description.trim());
        ticket.setOrderNumber(StringUtils.hasText(orderNumber) ? orderNumber.trim() : null);
        ticket.setStatus(SupportTicket.Status.OPEN);
        return supportTicketRepository.save(ticket);
    }

    @Transactional
    public SupportTicket updateStatus(long id, SupportTicket.Status status) {
        SupportTicket ticket = get(id);
        if (status != null) {
            ticket.setStatus(status);
        }
        return supportTicketRepository.save(ticket);
    }

    @Transactional
    public SupportTicket delete(long id) {
        SupportTicket ticket = get(id);
        supportTicketRepository.delete(ticket);
        return ticket;
    }
}
