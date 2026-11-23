package com.ai.fabric.realapps.incident.service;

import ai.fabric.dto.AIAccessSubjectContext;
import com.ai.fabric.realapps.incident.domain.IncidentScenario;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Keeps the caller identity distinct from the incident being inspected. */
@Service
public class IncidentCallerScopeResolver {
    private final IncidentScenarioCatalog catalog;
    private final IncidentDemoSessionRepository sessions;
    private final Clock clock;

    public IncidentCallerScopeResolver(IncidentScenarioCatalog catalog, IncidentDemoSessionRepository sessions, Clock clock) {
        this.catalog = catalog;
        this.sessions = sessions;
        this.clock = clock;
    }

    public IncidentScenario resolve(AIAccessSubjectContext auth) {
        if (auth == null || !StringUtils.hasText(auth.getSubjectId()) || !"public-demo".equals(auth.getTenantId()))
            throw denied();
        String incident;
        if ("TRUSTED_APPLICATION".equals(auth.getAuthMode()) && "SERVICE".equals(auth.getCallerType())) {
            incident = auth.getSubjectId();
        } else if ("TRUSTED_INTERACTIVE".equals(auth.getAuthMode()) && "END_USER".equals(auth.getCallerType())) {
            incident = sessions.findByOwnerIdAndExpiresAtAfter(auth.getSubjectId(), clock.instant())
                .orElseThrow(IncidentCallerScopeResolver::denied).getScenarioId();
        } else {
            throw denied();
        }
        IncidentScenario scenario = catalog.require(incident);
        if (!scenario.deploymentId().equals(auth.getDeploymentId())) throw denied();
        return scenario;
    }

    private static IllegalArgumentException denied() {
        return new IllegalArgumentException("Incident caller scope was denied");
    }
}
