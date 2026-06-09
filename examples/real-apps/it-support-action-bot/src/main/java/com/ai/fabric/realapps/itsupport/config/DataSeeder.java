package com.ai.fabric.realapps.itsupport.config;

import com.ai.fabric.realapps.itsupport.domain.Agent;
import com.ai.fabric.realapps.itsupport.domain.Ticket;
import com.ai.fabric.realapps.itsupport.repo.AgentRepository;
import com.ai.fabric.realapps.itsupport.repo.TicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final AgentRepository agentRepository;
    private final TicketRepository ticketRepository;

    @Override
    @Transactional
    public void run(String... args) {
        seedIfEmpty();
    }

    @Transactional
    public void seedIfEmpty() {
        if (agentRepository.count() == 0) {
            agentRepository.saveAll(List.of(
                Agent.builder().username("agent_alex").build(),
                Agent.builder().username("agent_maya").build(),
                Agent.builder().username("agent_sam").build()
            ));
            log.info("Seeded agents");
        }

        if (ticketRepository.count() == 0) {
            ticketRepository.saveAll(List.of(
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
            ));
            log.info("Seeded tickets");
        }
    }
}
