package com.ai.fabric.realapps.dbactionregistry.service;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultPresentationHint;
import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.intent.action.connector.ConnectorActionParamDefinition;
import ai.fabric.intent.action.connector.registry.service.ConnectorActionRegistryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DbActionRegistryLabService {

    private static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    private static final String STATUS_APPROVED = "APPROVED";

    private final ConnectorActionRegistryService registryService;
    private final AIActionRegistry actionRegistry;
    private final CustomerTicketActionRuntime runtime;
    private final Map<String, ProposalSummary> proposals = new ConcurrentHashMap<>();

    public DbActionRegistryLabService(ConnectorActionRegistryService registryService,
                                      AIActionRegistry actionRegistry,
                                      CustomerTicketActionRuntime runtime) {
        this.registryService = registryService;
        this.actionRegistry = actionRegistry;
        this.runtime = runtime;
    }

    public List<TemplateSummary> templates() {
        return List.of(
            new TemplateSummary("ticket.lookup", "Read a customer ticket without side effects."),
            new TemplateSummary("ticket.escalate", "Escalate a customer ticket; requires approval and runtime confirmation.")
        );
    }

    public ProposalSummary proposeTemplate(String templateName) {
        ConnectorActionDefinition definition = template(templateName);
        String proposalId = "proposal-" + UUID.randomUUID();
        ProposalSummary summary = new ProposalSummary(proposalId, STATUS_PENDING_APPROVAL, definition);
        proposals.put(proposalId, summary);
        return summary;
    }

    public ProposalSummary approve(String proposalId) {
        ProposalSummary pending = proposal(proposalId);
        ConnectorActionDefinition saved = registryService.register(pending.definition());
        ProposalSummary approved = new ProposalSummary(pending.proposalId(), STATUS_APPROVED, saved);
        proposals.put(pending.proposalId(), approved);
        actionRegistry.refresh();
        return approved;
    }

    public DiscoverySummary discover() {
        List<ActionSummary> dbActions = registryService.list().stream()
            .map(ActionSummary::fromDefinition)
            .sorted(Comparator.comparing(ActionSummary::name))
            .toList();
        List<ActionSummary> runtimeActions = actionRegistry.getAllMetadata().stream()
            .map(ActionSummary::fromMetadata)
            .filter(action -> dbActions.stream().anyMatch(db -> db.name().equals(action.name())))
            .sorted(Comparator.comparing(ActionSummary::name))
            .toList();
        return new DiscoverySummary(dbActions, runtimeActions);
    }

    public ExecutionSummary execute(String actionName, Map<String, Object> params, boolean confirmed, String userId) {
        Optional<AIActionHandler> maybeHandler = actionRegistry.findHandler(actionName);
        if (maybeHandler.isEmpty()) {
            return ExecutionSummary.failure(actionName, "ACTION_NOT_REGISTERED", "Action is not registered in the AI Fabric runtime registry.");
        }

        AIActionHandler handler = maybeHandler.get();
        Map<String, Object> safeParams = params != null ? Map.copyOf(params) : Map.of();
        ActionContext context = new ActionContext(null, null, safeParams);
        if (handler.requiresConfirmation() && !confirmed) {
            return new ExecutionSummary(
                actionName,
                false,
                true,
                handler.getConfirmationMessage(safeParams, context),
                "CONFIRMATION_REQUIRED",
                Map.of()
            );
        }

        ActionResult result = handler.executeAction(safeParams, context.withActionParams(contextParams(safeParams, userId)));
        Map<String, Object> data = result != null && result.getData() != null ? result.getData().toMap() : Map.of();
        return new ExecutionSummary(
            actionName,
            result != null && result.isSuccess(),
            false,
            result != null ? result.getMessage() : "Action returned no result.",
            result != null ? result.getErrorCode() : "ACTION_EXECUTION_FAILED",
            data
        );
    }

    public DiscoverySummary deregister(String actionName) {
        registryService.deregister(actionName);
        actionRegistry.refresh();
        return discover();
    }

    public List<Map<String, Object>> customerTickets() {
        return runtime.tickets();
    }

    public void resetRuntime() {
        runtime.reset();
    }

    private Map<String, Object> contextParams(Map<String, Object> params, String userId) {
        Map<String, Object> out = new LinkedHashMap<>(params != null ? params : Map.of());
        if (StringUtils.hasText(userId)) {
            out.put("userId", userId.trim());
        }
        return Map.copyOf(out);
    }

    private ProposalSummary proposal(String proposalId) {
        if (!StringUtils.hasText(proposalId)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "proposalId is required.");
        }
        ProposalSummary proposal = proposals.get(proposalId.trim());
        if (proposal == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Proposal not found: " + proposalId.trim());
        }
        return proposal;
    }

    private ConnectorActionDefinition template(String templateName) {
        String normalized = StringUtils.hasText(templateName) ? templateName.trim().toLowerCase() : "";
        return switch (normalized) {
            case "ticket.lookup" -> action(
                "ticket.lookup",
                "Read a customer ticket from the customer-owned support system.",
                "support",
                ActionAccessMode.READ,
                false,
                null,
                List.of(param("ticketId", "Customer support ticket id.", AIActionParamType.STRING, true, "TCK-[0-9]+"))
            );
            case "ticket.escalate" -> action(
                "ticket.escalate",
                "Escalate a customer ticket to another queue in the customer-owned support system.",
                "support",
                ActionAccessMode.READ_WRITE,
                true,
                "Escalate ticket {{ticketId}} to {{targetQueue}}?",
                List.of(
                    param("ticketId", "Customer support ticket id.", AIActionParamType.STRING, true, "TCK-[0-9]+"),
                    param("targetQueue", "Queue that should receive the escalation.", AIActionParamType.STRING, true, null)
                )
            );
            default -> throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Unknown template: " + templateName);
        };
    }

    private ConnectorActionDefinition action(String name,
                                             String description,
                                             String category,
                                             ActionAccessMode accessMode,
                                             boolean requiresConfirmation,
                                             String confirmationMessage,
                                             List<ConnectorActionParamDefinition> params) {
        return new ConnectorActionDefinition(
            name,
            name,
            description,
            category,
            accessMode,
            requiresConfirmation,
            confirmationMessage,
            params,
            false,
            accessMode.isGroundingEligibleByDefault(),
            accessMode == ActionAccessMode.READ,
            accessMode == ActionAccessMode.WRITE_ONLY ? ActionResultPresentationHint.STATUS : ActionResultPresentationHint.DEFAULT,
            null,
            null,
            null,
            List.of(),
            null
        );
    }

    private ConnectorActionParamDefinition param(String name,
                                                 String description,
                                                 AIActionParamType type,
                                                 boolean required,
                                                 String pattern) {
        return new ConnectorActionParamDefinition(
            name,
            description,
            type,
            required,
            false,
            pattern,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            false,
            null,
            Map.of(),
            List.of(),
            false,
            List.of(),
            null
        );
    }

    public record TemplateSummary(String name, String description) {
    }

    public record ProposalSummary(String proposalId, String status, ConnectorActionDefinition definition) {
    }

    public record DiscoverySummary(List<ActionSummary> dbActions, List<ActionSummary> runtimeActions) {
    }

    public record ActionSummary(String name,
                                String category,
                                ActionAccessMode accessMode,
                                boolean confirmationRequired,
                                Set<String> requiredParameters) {

        static ActionSummary fromDefinition(ConnectorActionDefinition definition) {
            Set<String> required = definition.params().stream()
                .filter(ConnectorActionParamDefinition::required)
                .map(ConnectorActionParamDefinition::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            return new ActionSummary(
                definition.name(),
                definition.category(),
                definition.accessMode(),
                definition.requiresConfirmation(),
                Set.copyOf(required)
            );
        }

        static ActionSummary fromMetadata(AIActionMetaData metadata) {
            return new ActionSummary(
                metadata.getName(),
                metadata.getCategory(),
                metadata.getAccessMode(),
                metadata.isConfirmationRequired(),
                metadata.getRequiredParameters()
            );
        }
    }

    public record ExecutionSummary(String actionName,
                                   boolean success,
                                   boolean confirmationRequired,
                                   String message,
                                   String errorCode,
                                   Map<String, Object> data) {

        static ExecutionSummary failure(String actionName, String errorCode, String message) {
            return new ExecutionSummary(actionName, false, false, message, errorCode, Map.of());
        }
    }
}
