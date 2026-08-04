package com.ai.fabric.realapps.mcpserver.service;

import com.ai.fabric.realapps.mcpserver.domain.SandboxServiceState;
import com.ai.fabric.realapps.mcpserver.repository.SandboxServiceStateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SandboxOperationsService {

    private static final Pattern SANDBOX_ID = Pattern.compile(
        "^mcp-demo-[a-z0-9-]{8,110}$"
    );
    private static final Set<String> SERVICES = Set.of(
        "catalog",
        "checkout",
        "payments"
    );
    private static final Map<String, String> VERSIONS = Map.of(
        "catalog", "2026.08.4",
        "checkout", "2026.08.7",
        "payments", "2026.08.3"
    );

    private final SandboxServiceStateRepository repository;

    @Value("${app.sandbox.ttl:PT6H}")
    private Duration ttl;

    public SandboxOperationsService(
        SandboxServiceStateRepository repository
    ) {
        this.repository = repository;
    }

    @Transactional
    public ServiceStatus status(String sandboxId, String serviceName) {
        SandboxServiceState state = requireState(sandboxId, serviceName);
        touch(state);
        return toStatus(repository.save(state));
    }

    @Transactional(readOnly = true)
    public IncidentList incidents(String sandboxId, String serviceName) {
        requireSandboxId(sandboxId);
        String service = requireService(serviceName);
        SandboxServiceState state = repository.findById(id(sandboxId, service))
            .orElse(null);
        int revision = state != null ? state.getRevision() : 1;
        List<IncidentSummary> incidents = switch (service) {
            case "checkout" -> List.of(
                new IncidentSummary(
                    "INC-2408",
                    "MEDIUM",
                    "Elevated checkout latency after a configuration rollout.",
                    "2026-08-03T20:12:00Z"
                )
            );
            case "payments" -> List.of(
                new IncidentSummary(
                    "INC-2399",
                    "HIGH",
                    "Intermittent sandbox authorization timeout.",
                    "2026-08-02T11:35:00Z"
                )
            );
            default -> List.of();
        };
        return new IncidentList(service, revision, incidents);
    }

    @Transactional
    public RestartOutcome restart(
        String sandboxId,
        String serviceName,
        Integer expectedRevision
    ) {
        SandboxServiceState state = requireState(sandboxId, serviceName);
        if (expectedRevision != null
            && expectedRevision != state.getRevision()) {
            throw new IllegalStateException(
                "Sandbox service revision changed before restart"
            );
        }
        state.setStatus("HEALTHY");
        state.setRevision(state.getRevision() + 1);
        state.setRestartCount(state.getRestartCount() + 1);
        state.setLastRestartAt(Instant.now());
        touch(state);
        SandboxServiceState saved = repository.saveAndFlush(state);
        return new RestartOutcome(
            saved.getServiceName(),
            saved.getStatus(),
            saved.getCurrentVersion(),
            saved.getRevision(),
            saved.getRestartCount(),
            saved.getLastRestartAt(),
            "Sandbox service restart completed"
        );
    }

    @Scheduled(cron = "${app.sandbox.cleanup-cron:0 41 * * * *}")
    @Transactional
    public void cleanupExpired() {
        repository.deleteAll(
            repository.findByLastTouchedAtBefore(Instant.now().minus(ttl))
        );
    }

    private SandboxServiceState requireState(
        String sandboxId,
        String serviceName
    ) {
        String sandbox = requireSandboxId(sandboxId);
        String service = requireService(serviceName);
        return repository.findById(id(sandbox, service)).orElseGet(() -> {
            SandboxServiceState state = new SandboxServiceState();
            state.setId(id(sandbox, service));
            state.setSandboxId(sandbox);
            state.setServiceName(service);
            state.setStatus("HEALTHY");
            state.setCurrentVersion(VERSIONS.get(service));
            state.setRevision(1);
            state.setRestartCount(0);
            state.setLastTouchedAt(Instant.now());
            return repository.saveAndFlush(state);
        });
    }

    private void touch(SandboxServiceState state) {
        state.setLastTouchedAt(Instant.now());
    }

    private ServiceStatus toStatus(SandboxServiceState state) {
        int openIncidents = "payments".equals(state.getServiceName()) ? 1 : 0;
        return new ServiceStatus(
            state.getServiceName(),
            state.getStatus(),
            state.getCurrentVersion(),
            state.getRevision(),
            openIncidents,
            state.getRestartCount(),
            state.getLastRestartAt()
        );
    }

    private String requireSandboxId(String value) {
        if (value == null || !SANDBOX_ID.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Invalid sandbox identifier");
        }
        return value.trim();
    }

    private String requireService(String value) {
        String service = value == null
            ? ""
            : value.trim().toLowerCase(Locale.ROOT);
        if (!SERVICES.contains(service)) {
            throw new IllegalArgumentException("Unsupported sandbox service");
        }
        return service;
    }

    private String id(String sandboxId, String serviceName) {
        return sandboxId + ":" + serviceName;
    }

    public record ServiceStatus(
        String service,
        String status,
        String version,
        int revision,
        int openIncidents,
        int restartCount,
        Instant lastRestartAt
    ) {
    }

    public record IncidentSummary(
        String incidentId,
        String severity,
        String summary,
        String observedAt
    ) {
    }

    public record IncidentList(
        String service,
        int serviceRevision,
        List<IncidentSummary> incidents
    ) {
        public IncidentList {
            incidents = incidents == null ? List.of() : List.copyOf(incidents);
        }
    }

    public record RestartOutcome(
        String serviceName,
        String status,
        String currentVersion,
        int revision,
        int restartCount,
        Instant lastRestartAt,
        String message
    ) {
    }
}
