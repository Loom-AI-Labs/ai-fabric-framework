package com.ai.fabric.realapps.chat.support.repo;

import com.ai.fabric.realapps.chat.support.domain.SupportTicket;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    List<SupportTicket> findByUserIdOrderByCreatedAtDesc(String userId);
}
