package com.ai.fabric.realapps.itsupport.action;

import com.ai.fabric.realapps.itsupport.domain.Ticket;
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

@AIAction(
    name = "set_ticket_priority",
    description = "Change the priority of a ticket",
    category = "it-support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class SetPriorityActionHandler {

    private final TicketService ticketService;

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        return userId != null && !userId.isBlank();
    }

    @ActionConfirmation
    public String confirm(@Param(value = "ticketNumber", required = true, description = "Numeric ticket number (ex: 1001)") Long ticketNumber) {
        return "Update priority for ticket " + ticketNumber + "?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "ticketNumber", required = true, description = "Numeric ticket number (ex: 1001)") Long ticketNumber,
        @Param(value = "priority", required = true, description = "LOW|MEDIUM|HIGH|URGENT") String priority,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            Ticket.Priority parsed = parsePriority(priority);
            Ticket updated = ticketService.setPriority(ticketNumber, parsed);
            return ActionResult.builder()
                .success(true)
                .message("Ticket priority updated")
                .data(ActionResultContracts.object(Map.of(
                    "ticketNumber", updated.getTicketNumber(),
                    "priority", updated.getPriority().name()
                )))
                .build();
        } catch (Exception e) {
            log.error("Set priority failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to set priority: " + e.getMessage())
                .errorCode("SET_PRIORITY_FAILED")
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
}
