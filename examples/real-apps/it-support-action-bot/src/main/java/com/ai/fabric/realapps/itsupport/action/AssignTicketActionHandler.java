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
    name = "assign_ticket",
    description = "Assign a ticket to an agent and mark it IN_PROGRESS",
    category = "it-support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class AssignTicketActionHandler {

    private final TicketService ticketService;

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        String userId = context != null ? context.userId() : null;
        return userId != null && !userId.isBlank();
    }

    @ActionConfirmation
    public String confirm(
        @Param(value = "ticketNumber", required = true, description = "Numeric ticket number (ex: 1001)") Long ticketNumber,
        @Param(value = "assigneeUsername", required = true, description = "Agent username (ex: agent_maya)") String assignee
    ) {
        return "Assign ticket " + ticketNumber + " to " + assignee + "?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "ticketNumber", required = true, description = "Numeric ticket number (ex: 1001)") Long ticketNumber,
        @Param(value = "assigneeUsername", required = true, description = "Agent username (ex: agent_maya)") String assigneeUsername,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            Ticket updated = ticketService.assign(ticketNumber, assigneeUsername);
            return ActionResult.builder()
                .success(true)
                .message("Ticket assigned")
                .data(ActionResultContracts.object(Map.of(
                    "ticketNumber", updated.getTicketNumber(),
                    "assignedTo", updated.getAssignedTo(),
                    "status", updated.getStatus().name()
                )))
                .build();
        } catch (Exception e) {
            log.error("Assign ticket failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to assign ticket: " + e.getMessage())
                .errorCode("ASSIGN_TICKET_FAILED")
                .build();
        }
    }
}
