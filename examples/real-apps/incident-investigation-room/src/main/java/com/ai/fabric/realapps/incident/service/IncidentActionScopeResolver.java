package com.ai.fabric.realapps.incident.service;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.intent.action.ActionContext;
import com.ai.fabric.realapps.incident.domain.AuthorizedIncidentScope;
import com.ai.fabric.realapps.incident.domain.IncidentScenario;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class IncidentActionScopeResolver {

    private static final Set<String> TRUSTED_MODES = Set.of(
        "TRUSTED_APPLICATION",
        "TRUSTED_INTERACTIVE"
    );
    private static final Set<String> CALLER_TYPES = Set.of(
        "SERVICE",
        "END_USER"
    );

    private final IncidentScenarioCatalog catalog;

    public IncidentActionScopeResolver(IncidentScenarioCatalog catalog) {
        this.catalog = catalog;
    }

    public boolean allowed(ActionContext context, String actionName) {
        try {
            resolve(context, actionName);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public AuthorizedIncidentScope resolve(
        ActionContext context,
        String actionName
    ) {
        if (context == null || !StringUtils.hasText(actionName)) {
            throw denied();
        }
        AIAccessSubjectContext auth = context.authContext();
        if (auth == null
            || !StringUtils.hasText(auth.getSubjectId())
            || !TRUSTED_MODES.contains(auth.getAuthMode())
            || !CALLER_TYPES.contains(auth.getCallerType())
            || !"public-demo".equals(auth.getTenantId())
            || auth.getGrantedScopes() == null
            || !auth.getGrantedScopes().contains("action:" + actionName)) {
            throw denied();
        }
        IncidentScenario scenario = catalog.require(auth.getSubjectId());
        if (!scenario.deploymentId().equals(auth.getDeploymentId())) {
            throw denied();
        }
        return new AuthorizedIncidentScope(
            auth.getTenantId(),
            scenario.id(),
            scenario.deploymentId(),
            scenario.sourceRevision()
        );
    }

    private IllegalArgumentException denied() {
        return new IllegalArgumentException(
            "Incident data-source access was denied"
        );
    }
}
