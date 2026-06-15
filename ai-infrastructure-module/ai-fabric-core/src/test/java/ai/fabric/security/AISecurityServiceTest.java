package ai.fabric.security;

import ai.fabric.config.SecurityProperties;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AISecurityRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class AISecurityServiceTest {

    @Test
    void shouldAllowAnonymousRequestsWhenSessionIdPresent() {
        SecurityProperties properties = new SecurityProperties();
        properties.setBlockOnPiiDetection(false);

        AISecurityService service = new AISecurityService(
            null,
            Clock.fixed(Instant.parse("2026-01-16T00:00:00Z"), ZoneOffset.UTC),
            properties
        );

        AISecurityRequest request = AISecurityRequest.builder()
            .requestId("req-1")
            .authContext(AIAccessSubjectContext.builder()
                .sessionId("session-123")
                .subjectType("ANONYMOUS")
                .build())
            .content("hello")
            .operationType("INTENT_QUERY")
            .build();

        var response = service.analyzeRequest(request);

        assertThat(response).isNotNull();
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getShouldBlock()).isFalse();
        assertThat(response.getSubjectId()).isEqualTo("session-123");
    }
}
