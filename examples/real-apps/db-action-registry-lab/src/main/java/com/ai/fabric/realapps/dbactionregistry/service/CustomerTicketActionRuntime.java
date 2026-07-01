package com.ai.fabric.realapps.dbactionregistry.service;

import ai.fabric.intent.action.connector.ActionConnectorProtocol;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CustomerTicketActionRuntime {

    private final Map<String, TicketState> tickets = new ConcurrentHashMap<>();

    public CustomerTicketActionRuntime() {
        reset();
    }

    public Map<String, Object> execute(String actionId, Map<String, Object> params, Map<String, Object> trace) {
        return switch (normalize(actionId)) {
            case "ticket.lookup" -> lookup(params, trace);
            case "ticket.escalate" -> escalate(params, trace);
            default -> failure("ACTION_NOT_SUPPORTED", "Customer runtime does not expose action '" + safe(actionId) + "'.");
        };
    }

    public List<Map<String, Object>> tickets() {
        return tickets.values().stream()
            .map(TicketState::toMap)
            .toList();
    }

    public void reset() {
        tickets.clear();
        tickets.put("TCK-1001", new TicketState("TCK-1001", "tenant-a", "Acme Cloud", "OPEN", "P2", "support", 0));
        tickets.put("TCK-1002", new TicketState("TCK-1002", "tenant-a", "Northwind", "OPEN", "P3", "support", 0));
    }

    private Map<String, Object> lookup(Map<String, Object> params, Map<String, Object> trace) {
        String ticketId = text(params, "ticketId");
        if (!StringUtils.hasText(ticketId)) {
            return failure("VALIDATION_FAILED", "ticketId is required.");
        }

        TicketState ticket = tickets.get(ticketId);
        if (ticket == null) {
            return failure("TICKET_NOT_FOUND", "Ticket not found.");
        }

        Map<String, Object> data = ticket.toMap();
        data.put("traceRequestId", text(trace, ActionConnectorProtocol.TRACE_REQUEST_ID));
        return success("Ticket " + ticket.ticketId() + " loaded.", data);
    }

    private Map<String, Object> escalate(Map<String, Object> params, Map<String, Object> trace) {
        String ticketId = text(params, "ticketId");
        String targetQueue = text(params, "targetQueue");
        if (!StringUtils.hasText(ticketId) || !StringUtils.hasText(targetQueue)) {
            return failure("VALIDATION_FAILED", "ticketId and targetQueue are required.");
        }

        TicketState current = tickets.get(ticketId);
        if (current == null) {
            return failure("TICKET_NOT_FOUND", "Ticket not found.");
        }

        TicketState updated = new TicketState(
            current.ticketId(),
            current.tenantId(),
            current.customerName(),
            "ESCALATED",
            "P1",
            targetQueue,
            current.escalationCount() + 1
        );
        tickets.put(ticketId, updated);

        Map<String, Object> data = updated.toMap();
        data.put("previousQueue", current.queue());
        data.put("auditEventId", "audit-" + updated.ticketId() + "-" + updated.escalationCount());
        data.put("traceUserId", text(trace, ActionConnectorProtocol.TRACE_USER_ID));
        return success("Ticket " + updated.ticketId() + " escalated to " + targetQueue + ".", data);
    }

    private Map<String, Object> success(String message, Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ActionConnectorProtocol.KEY_SUCCESS, true);
        out.put(ActionConnectorProtocol.KEY_MESSAGE, message);
        out.put(ActionConnectorProtocol.KEY_DATA, data != null ? data : Map.of());
        return out;
    }

    private Map<String, Object> failure(String errorCode, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ActionConnectorProtocol.KEY_SUCCESS, false);
        out.put(ActionConnectorProtocol.KEY_ERROR_CODE, errorCode);
        out.put(ActionConnectorProtocol.KEY_MESSAGE, message);
        return out;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : "";
    }

    private String text(Map<String, Object> source, String key) {
        if (source == null || !StringUtils.hasText(key)) {
            return "";
        }
        Object raw = source.get(key);
        return raw != null && StringUtils.hasText(raw.toString()) ? raw.toString().trim() : "";
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unknown";
    }

    public record TicketState(String ticketId,
                              String tenantId,
                              String customerName,
                              String status,
                              String severity,
                              String queue,
                              int escalationCount) {

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ticketId", ticketId);
            out.put("tenantId", tenantId);
            out.put("customerName", customerName);
            out.put("status", status);
            out.put("severity", severity);
            out.put("queue", queue);
            out.put("escalationCount", escalationCount);
            return out;
        }
    }
}
