package com.ai.fabric.realapps.itsupport.config;

import com.ai.fabric.realapps.itsupport.domain.Agent;
import com.ai.fabric.realapps.itsupport.domain.Ticket;
import com.ai.fabric.realapps.itsupport.repo.AgentRepository;
import com.ai.fabric.realapps.itsupport.repo.TicketRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final AgentRepository agentRepository;
    private final TicketRepository ticketRepository;
    private final TransactionTemplate transactionTemplate;

    public DataSeeder(AgentRepository agentRepository,
                      TicketRepository ticketRepository,
                      PlatformTransactionManager transactionManager) {
        this.agentRepository = agentRepository;
        this.ticketRepository = ticketRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(String... args) {
        seedIfEmpty();
    }

    public synchronized void seedIfEmpty() {
        transactionTemplate.executeWithoutResult(ignored -> seedMissingRecords());
    }

    private void seedMissingRecords() {
        List<Agent> missingAgents = List.of("agent_alex", "agent_maya", "agent_sam").stream()
            .filter(username -> !agentRepository.existsByUsername(username))
            .map(username -> Agent.builder().username(username).build())
            .toList();
        if (!missingAgents.isEmpty()) {
            agentRepository.saveAll(missingAgents);
            log.info("Seeded agents");
        }

        List<Ticket> missingTickets = List.of(
            Ticket.builder()
                .ticketNumber(1001L)
                .title("VPN disconnects every 10 minutes")
                .description("User reports VPN drops frequently after the latest Windows update.")
                .priority(Ticket.Priority.HIGH)
                .status(Ticket.Status.OPEN)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build(),
            Ticket.builder()
                .ticketNumber(1002L)
                .title("Database backup job failing")
                .description("Nightly backup failed with permission denied; production risk.")
                .priority(Ticket.Priority.URGENT)
                .status(Ticket.Status.IN_PROGRESS)
                .assignedTo("agent_maya")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build(),
            Ticket.builder()
                .ticketNumber(1003L)
                .title("Printer not showing in devices list")
                .description("New printer added but not visible for the user; needs driver install.")
                .priority(Ticket.Priority.MEDIUM)
                .status(Ticket.Status.OPEN)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build()
        ).stream()
            .filter(ticket -> !ticketRepository.existsByTicketNumber(ticket.getTicketNumber()))
            .toList();
        if (!missingTickets.isEmpty()) {
            ticketRepository.saveAll(missingTickets);
            log.info("Seeded tickets");
        }
    }
}
