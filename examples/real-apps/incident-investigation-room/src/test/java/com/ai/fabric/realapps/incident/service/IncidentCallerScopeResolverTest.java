package com.ai.fabric.realapps.incident.service;

import ai.fabric.dto.AIAccessSubjectContext;
import com.ai.fabric.realapps.incident.domain.IncidentDemoSession;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncidentCallerScopeResolverTest {
    private final Instant now = Instant.parse("2026-11-23T12:00:00Z");
    private final IncidentDemoSessionRepository sessions = mock(IncidentDemoSessionRepository.class);
    private final IncidentCallerScopeResolver resolver = new IncidentCallerScopeResolver(new IncidentScenarioCatalog(),
        sessions, Clock.fixed(now, ZoneOffset.UTC));

    @Test void interactiveCallerUsesOnlyItsOwnedActiveSessionNotAnIncidentIdentifier() {
        when(sessions.findByOwnerIdAndExpiresAtAfter("owner", now)).thenReturn(Optional.of(new IncidentDemoSession(
            "session", "owner", "conversation", "checkout-regression", now.minusSeconds(60), now.plusSeconds(60))));
        var auth = interactive("owner", "commerce-api-prod", "public-demo");
        assertThat(resolver.resolve(auth).id()).isEqualTo("checkout-regression");
        assertThat(auth.getSubjectId()).isEqualTo("owner");
        assertThatThrownBy(() -> resolver.resolve(interactive("checkout-regression", "commerce-api-prod", "public-demo")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(interactive("owner", "commerce-api-staging", "public-demo")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(interactive("owner", "commerce-api-prod", "foreign")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(interactive("unknown-or-expired", "commerce-api-prod", "public-demo")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void backendServiceKeepsItsExplicitIncidentBoundaryWithoutUsingAnInteractiveSession() {
        var auth = AIAccessSubjectContext.builder().subjectId("checkout-regression").subjectType("incident")
            .authMode("TRUSTED_APPLICATION").callerType("SERVICE").tenantId("public-demo").deploymentId("commerce-api-prod").build();
        assertThat(resolver.resolve(auth).id()).isEqualTo("checkout-regression");
        verifyNoInteractions(sessions);
        assertThatThrownBy(() -> resolver.resolve(AIAccessSubjectContext.builder().subjectId("checkout-regression")
            .authMode("TRUSTED_INTERACTIVE").callerType("SERVICE").tenantId("public-demo").deploymentId("commerce-api-prod").build()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private AIAccessSubjectContext interactive(String caller, String deployment, String tenant) {
        return AIAccessSubjectContext.builder().subjectId(caller).subjectType("END_USER").authMode("TRUSTED_INTERACTIVE")
            .callerType("END_USER").tenantId(tenant).deploymentId(deployment).build();
    }
}
