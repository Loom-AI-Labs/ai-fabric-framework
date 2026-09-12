package com.ai.fabric.realapps.incident.service;

import com.ai.fabric.realapps.incident.domain.AuthorizedIncidentScope;
import com.ai.fabric.realapps.incident.domain.IncidentEvent;
import com.ai.fabric.realapps.incident.domain.IncidentEventQuery;
import java.util.List;

public interface IncidentEventRepository {

    List<IncidentEvent> findAuthorized(
        IncidentEventQuery query,
        AuthorizedIncidentScope scope
    );

    List<IncidentEvent> previewAuthorized(AuthorizedIncidentScope scope);

    int countOutsideBoundary(AuthorizedIncidentScope scope);

    int totalCount();
}
