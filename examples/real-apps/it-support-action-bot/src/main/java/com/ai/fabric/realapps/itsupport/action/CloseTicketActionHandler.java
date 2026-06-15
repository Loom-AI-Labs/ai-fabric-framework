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
    name = "close_ticket",
    description = "Close a ticket and add a resolution note",
    category = "it-support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class CloseTicketActionHandler {

    private final TicketService ticketService;

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        return userId != null && !userId.isBlank();
    }

    @ActionConfirmation
    public String confirm(@Param(value = "ticketNumber", required = true, description = "Numeric ticket number (ex: 1003)") Long ticketNumber) {
        return "Close ticket " + ticketNumber + "?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "ticketNumber", required = true, description = "Numeric ticket number (ex: 1003)") Long ticketNumber,
        @Param(value = "resolutionNote", description = "What fixed the issue (optional)") String resolutionNote,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            Ticket updated = ticketService.close(ticketNumber, resolutionNote);
            return ActionResult.builder()
                .success(true)
                .message("Ticket closed")
                .data(ActionResultContracts.object(Map.of(
                    "ticketNumber", updated.getTicketNumber(),
                    "status", updated.getStatus().name()
                )))
                .build();
        } catch (Exception e) {
            log.error("Close ticket failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to close ticket: " + e.getMessage())
                .errorCode("CLOSE_TICKET_FAILED")
                .build();
        }
    }
}
