package com.ai.fabric.realapps.itsupport.action;

import com.ai.fabric.realapps.itsupport.domain.Ticket;
import com.ai.fabric.realapps.itsupport.repo.TicketRepository;
import com.ai.fabric.realapps.itsupport.service.TicketService;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@AIAction(
    name = "create_ticket",
    description = "Create a new IT support ticket",
    category = "it-support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class CreateTicketActionHandler {

    private final TicketService ticketService;
    private final TicketRepository ticketRepository;

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        return userId != null && !userId.isBlank();
    }

    @ActionConfirmation
    public String confirm(@Param(value = "title", required = true, description = "Short title for the issue") String title) {
        return title != null ? "Create a new ticket: " + title + "?" : "Create a new ticket?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "title", required = true, description = "Short title for the issue") String title,
        @Param(value = "description", description = "Longer description (optional)") String description,
        @Param(value = "priority", description = "LOW|MEDIUM|HIGH|URGENT (optional)") String priority,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            Ticket.Priority parsed = parsePriority(priority);
            long ticketNumber = nextTicketNumber();
            Ticket created = ticketService.createTicket(ticketNumber, title, description, parsed);

            return ActionResult.builder()
                .success(true)
                .message("Ticket created")
                .data(ActionResultContracts.object(Map.of(
                    "ticketNumber", created.getTicketNumber(),
                    "status", created.getStatus().name(),
                    "priority", created.getPriority().name()
                )))
                .build();
        } catch (Exception e) {
            log.error("Create ticket failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to create ticket: " + e.getMessage())
                .errorCode("CREATE_TICKET_FAILED")
                .build();
        }
    }

    private Ticket.Priority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return Ticket.Priority.MEDIUM;
        }
        try {
            return Ticket.Priority.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Ticket.Priority.MEDIUM;
        }
    }

    private long nextTicketNumber() {
        long base = 1000L + ThreadLocalRandom.current().nextLong(1, 9000);
        for (int attempt = 0; attempt < 25; attempt++) {
            long candidate = base + attempt;
            if (!ticketRepository.existsByTicketNumber(candidate)) {
                return candidate;
            }
        }
        // fallback (rare)
        long candidate;
        do {
            candidate = 1000L + ThreadLocalRandom.current().nextLong(1, 9000);
        } while (ticketRepository.existsByTicketNumber(candidate));
        return candidate;
    }
}
