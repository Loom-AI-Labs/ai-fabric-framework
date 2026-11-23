package com.ai.fabric.realapps.incident.service;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.intent.action.ActionContext;
import com.ai.fabric.realapps.incident.domain.AuthorizedIncidentScope;
import com.ai.fabric.realapps.incident.domain.IncidentScenario;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class IncidentActionScopeResolver {

    private final IncidentCallerScopeResolver callerScopes;

    public IncidentActionScopeResolver(IncidentCallerScopeResolver callerScopes) {
        this.callerScopes = callerScopes;
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
            || auth.getGrantedScopes() == null
            || !auth.getGrantedScopes().contains("action:" + actionName)) {
            throw denied();
        }
        IncidentScenario scenario = callerScopes.resolve(auth);
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
