package com.ai.fabric.realapps.itsupport.service;

import com.ai.fabric.realapps.itsupport.domain.Ticket;
import com.ai.fabric.realapps.itsupport.repo.AgentRepository;
import com.ai.fabric.realapps.itsupport.repo.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final AgentRepository agentRepository;

    public List<Ticket> listTickets() {
        return ticketRepository.findAll();
    }

    public Optional<Ticket> getByTicketNumber(long ticketNumber) {
        return ticketRepository.findByTicketNumber(ticketNumber);
    }

    @Transactional
    public Ticket createTicket(long ticketNumber, String title, String description, Ticket.Priority priority) {
        Ticket ticket = Ticket.builder()
            .ticketNumber(ticketNumber)
            .title(title)
            .description(description)
            .priority(priority != null ? priority : Ticket.Priority.MEDIUM)
            .status(Ticket.Status.OPEN)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket assign(long ticketNumber, String assigneeUsername) {
        Ticket ticket = ticketRepository.findByTicketNumber(ticketNumber)
            .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketNumber));

        if (assigneeUsername == null || assigneeUsername.isBlank()) {
            throw new IllegalArgumentException("assigneeUsername is required");
        }
        if (!agentRepository.existsByUsername(assigneeUsername)) {
            throw new IllegalArgumentException("Agent not found: " + assigneeUsername);
        }

        ticket.setAssignedTo(assigneeUsername);
        ticket.setStatus(Ticket.Status.IN_PROGRESS);
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket setPriority(long ticketNumber, Ticket.Priority priority) {
        Ticket ticket = ticketRepository.findByTicketNumber(ticketNumber)
            .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketNumber));
        ticket.setPriority(priority != null ? priority : Ticket.Priority.MEDIUM);
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket escalate(long ticketNumber, String reason) {
        Ticket ticket = ticketRepository.findByTicketNumber(ticketNumber)
            .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketNumber));
        ticket.setEscalated(true);
        ticket.setEscalationReason(reason);
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket close(long ticketNumber, String resolutionNote) {
        Ticket ticket = ticketRepository.findByTicketNumber(ticketNumber)
            .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketNumber));
        ticket.setStatus(Ticket.Status.CLOSED);
        ticket.setResolutionNote(resolutionNote);
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }
}
