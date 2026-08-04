package com.ai.fabric.realapps.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.fabric.realapps.mcpserver.service.SandboxOperationsService;
import com.ai.fabric.realapps.mcpserver.service.SandboxOperationsTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:mcp-reference-test;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.mcp.api-key=test-mcp-key"
})
class SandboxOperationsIntegrationTest {

    @Autowired
    private SandboxOperationsService operations;

    @Autowired
    private SandboxOperationsTools tools;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void isolatesStateAndAppliesOneOptimisticRestart() {
        String first = "mcp-demo-11111111-1111-1111-1111-111111111111";
        String second = "mcp-demo-22222222-2222-2222-2222-222222222222";

        var initial = operations.status(first, "checkout");
        var restarted = operations.restart(
            first,
            "checkout",
            initial.revision()
        );

        assertThat(restarted.revision()).isEqualTo(2);
        assertThat(restarted.restartCount()).isEqualTo(1);
        assertThat(operations.status(second, "checkout").revision())
            .isEqualTo(1);
        assertThatThrownBy(() -> operations.restart(
            first,
            "checkout",
            initial.revision()
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("revision changed");
    }

    @Test
    void rejectsUntrustedSandboxAndServiceIdentifiers() {
        assertThatThrownBy(() -> operations.status("other-user", "checkout"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.status(
            "mcp-demo-11111111-1111-1111-1111-111111111111",
            "shell"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void remoteToolShapeMatchesTheAssistantActionCatalog() {
        String sandbox = "mcp-demo-33333333-3333-3333-3333-333333333333";

        Map<String, Object> status = objectMapper.convertValue(
            tools.status(sandbox, "checkout").structuredContent(),
            new TypeReference<>() { }
        );
        Map<String, Object> incidentResult = objectMapper.convertValue(
            tools.incidents(
                sandbox,
                "checkout"
            ).structuredContent(),
            new TypeReference<>() { }
        );
        List<Map<String, Object>> incidents =
            (List<Map<String, Object>>) incidentResult.get("incidents");

        assertThat(status)
            .containsKeys(
                "serviceName",
                "status",
                "currentVersion",
                "revision",
                "openIncidents",
                "restartCount",
                "lastRestartAt"
            )
            .doesNotContainKeys("service", "version");
        assertThat(incidentResult)
            .containsKeys("serviceName", "revision", "incidents")
            .doesNotContainKeys("service", "serviceRevision");
        assertThat(incidents).hasSize(1);
        assertThat(incidents.getFirst())
            .containsKeys("incidentId", "severity", "summary", "observedAt")
            .doesNotContainKeys("status", "openedAt");
    }
}
