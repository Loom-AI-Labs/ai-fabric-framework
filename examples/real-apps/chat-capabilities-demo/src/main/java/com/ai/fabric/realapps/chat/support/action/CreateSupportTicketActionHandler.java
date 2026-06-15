package com.ai.fabric.realapps.chat.support.action;

import com.ai.fabric.realapps.chat.support.domain.SupportTicket;
import com.ai.fabric.realapps.chat.support.service.SupportTicketService;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.ActionTargetRef;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionConfirmation;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "create_support_ticket",
    description = "Create a support ticket for an ecommerce issue",
    category = "support",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
@RequiredArgsConstructor
@Slf4j
public class CreateSupportTicketActionHandler {

    private final SupportTicketService supportTicketService;

    @ActionConfirmation
    public String confirm() {
        return "Create a support ticket?";
    }

    @ActionExecute
    public ActionResult execute(
        @Param(value = "issueType", description = "Issue type", required = true) String issueType,
        @Param(value = "description", description = "Issue description", required = true) String description,
        @Param(value = "orderNumber", description = "Order number or id") String orderNumber,
        ActionContext context
    ) {
        String userId = context != null ? context.userId() : null;
        try {
            SupportTicket created = supportTicketService.create(userId, issueType, description, orderNumber);
            String ticketId = created != null && created.getId() != null ? String.valueOf(created.getId()) : null;
            ActionTargetRef ticketTarget = ticketId != null
                ? new ActionTargetRef(ticketId, "support_ticket", "support ticket", Map.of("ticketId", ticketId))
                : null;
            return ActionResult.builder()
                .success(true)
                .message("Support ticket created")
                .data(ActionResultContracts.object(Map.of(
                    "ticketId", created.getId(),
                    "status", created.getStatus() != null ? created.getStatus().name() : null,
                    "issueType", created.getIssueType()
                )))
                .pinnedTargets(ticketTarget != null ? List.of(ticketTarget) : null)
                .build();
        } catch (Exception e) {
            log.error("Create support ticket failed for user {}", userId, e);
            return ActionResult.builder()
                .success(false)
                .message("Failed to create support ticket: " + e.getMessage())
                .errorCode("CREATE_SUPPORT_TICKET_FAILED")
                .build();
        }
    }
}
