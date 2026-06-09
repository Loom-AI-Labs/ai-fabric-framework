package com.ai.fabric.realapps.crm.repo;

import com.ai.fabric.realapps.crm.domain.CrmSupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepository extends JpaRepository<CrmSupportTicket, Long> {}
