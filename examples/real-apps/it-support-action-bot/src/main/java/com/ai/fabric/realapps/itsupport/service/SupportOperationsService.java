package com.ai.fabric.realapps.itsupport.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class SupportOperationsService {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final List<Runbook> runbooks = List.of(
        new Runbook("rb-password-reset", "Password reset", "Reset identity credentials and invalidate sessions."),
        new Runbook("rb-vpn-outage", "VPN outage", "Check identity provider status and escalate network incidents."),
        new Runbook("rb-billing-access", "Billing access", "Route billing permission requests to the account owner.")
    );

    public SupportOpsResult assist(TicketAssistRequest request) {
        TicketAssistRequest effective = request != null
            ? request
            : new TicketAssistRequest(null, null, null, true);
        String customerNote = requireText(effective.customerNote(), "customerNote");
        String severity = classifySeverity(customerNote);
        List<RunbookEvidence> evidence = effective.ragEnabled()
            ? retrieveRunbooks(customerNote)
            : List.of();
        List<String> actions = severity.equals("HIGH")
            ? List.of("assign_ticket", "escalate_ticket", "write_customer_safe_summary")
            : List.of("assign_ticket", "write_customer_safe_summary");
        return new SupportOpsResult(
            effective.ticketId(),
            severity,
            evidence,
            actions,
            true,
            customerSafeSummary(customerNote, evidence)
        );
    }

    private String classifySeverity(String note) {
        String normalized = note.toLowerCase(Locale.ROOT);
        if (normalized.contains("outage") || normalized.contains("cannot login") || normalized.contains("blocked")) {
            return "HIGH";
        }
        if (normalized.contains("slow") || normalized.contains("intermittent")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private List<RunbookEvidence> retrieveRunbooks(String note) {
        String normalized = note.toLowerCase(Locale.ROOT);
        return runbooks.stream()
            .filter(runbook -> normalized.contains("vpn") && runbook.id().equals("rb-vpn-outage")
                || normalized.contains("password") && runbook.id().equals("rb-password-reset")
                || normalized.contains("billing") && runbook.id().equals("rb-billing-access"))
            .map(runbook -> new RunbookEvidence(runbook.id(), runbook.title()))
            .toList();
    }

    private String customerSafeSummary(String customerNote, List<RunbookEvidence> evidence) {
        String redacted = EMAIL.matcher(customerNote).replaceAll("[REDACTED_EMAIL]");
        String evidenceIds = evidence.isEmpty()
            ? "no runbook evidence"
            : evidence.stream().map(RunbookEvidence::id).toList().toString();
        return "We reviewed your request using " + evidenceIds + ". Summary: " + redacted;
    }

    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private record Runbook(String id, String title, String body) {}

    public record TicketAssistRequest(
        String ticketId,
        String customerNote,
        Map<String, Object> internalOnlyFields,
        boolean ragEnabled
    ) {}

    public record RunbookEvidence(String id, String title) {}

    public record SupportOpsResult(
        String ticketId,
        String severity,
        List<RunbookEvidence> runbookEvidence,
        List<String> suggestedActions,
        boolean ticketActionsRequireConfirmation,
        String customerSafeSummary
    ) {}
}
