package com.ai.fabric.realapps.itsupport.repo;

import com.ai.fabric.realapps.itsupport.domain.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    Optional<Ticket> findByTicketNumber(Long ticketNumber);
    boolean existsByTicketNumber(Long ticketNumber);
}
