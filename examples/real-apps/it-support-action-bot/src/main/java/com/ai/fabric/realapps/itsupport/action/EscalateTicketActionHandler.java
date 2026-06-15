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
    name = "escalate_ticket",
    description = "Escalate a ticket to an on-call escalation path",
    category = "it-support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class EscalateTicketActionHandler {

    private final TicketService ticketService;

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        return userId != null && !userId.isBlank();
    }

    @ActionConfirmation
    public String confirm(@Param(value = "ticketNumber", required = true, description = "Numeric ticket number (ex: 1002)") Long ticketNumber) {
        return "Escalate ticket " + ticketNumber + "?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "ticketNumber", required = true, description = "Numeric ticket number (ex: 1002)") Long ticketNumber,
        @Param(value = "reason", description = "Why escalation is needed (optional)") String reason,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            Ticket updated = ticketService.escalate(ticketNumber, reason);
            return ActionResult.builder()
                .success(true)
                .message("Ticket escalated")
                .data(ActionResultContracts.object(Map.of(
                    "ticketNumber", updated.getTicketNumber(),
                    "escalated", updated.isEscalated()
                )))
                .build();
        } catch (Exception e) {
            log.error("Escalate failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to escalate ticket: " + e.getMessage())
                .errorCode("ESCALATE_FAILED")
                .build();
        }
    }
}
