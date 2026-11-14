package com.ai.fabric.realapps.dbactionregistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = DbActionRegistryLabApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DbActionRegistryLabSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    @DisplayName("E2E Smoke Test: Full DB action lifecycle over HTTP (Propose -> Approve -> Discover -> Execute)")
    void endToEndDbActionRegistryWorkflow() throws Exception {
        String baseUrl = "http://localhost:" + port;

        // 1. Propose Action
        HttpRequest proposeReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/demo/db-action-registry/proposals/ticket.lookup"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> proposeRes = httpClient.send(proposeReq, HttpResponse.BodyHandlers.ofString());
        assertThat(proposeRes.statusCode()).isEqualTo(200);

        JsonNode proposeJson = objectMapper.readTree(proposeRes.body());
        assertThat(proposeJson.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        String proposalId = proposeJson.get("proposalId").asText();

        // 2. Approve Proposal
        HttpRequest approveReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/demo/db-action-registry/proposals/" + proposalId + "/approve"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> approveRes = httpClient.send(approveReq, HttpResponse.BodyHandlers.ofString());
        assertThat(approveRes.statusCode()).isEqualTo(200);

        JsonNode approveJson = objectMapper.readTree(approveRes.body());
        assertThat(approveJson.get("status").asText()).isEqualTo("APPROVED");

        // 3. Discover Registry Action
        HttpRequest discoverReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/demo/db-action-registry/discovery"))
                .GET()
                .build();
        HttpResponse<String> discoverRes = httpClient.send(discoverReq, HttpResponse.BodyHandlers.ofString());
        assertThat(discoverRes.statusCode()).isEqualTo(200);

        JsonNode discoveryJson = objectMapper.readTree(discoverRes.body());
        assertThat(discoveryJson.get("dbActions").get(0).get("name").asText()).isEqualTo("ticket.lookup");

        // 4. Execute Action
        String executeBody = """
            {
              "params": {
                "ticketId": "TCK-1001"
              },
              "confirmed": false,
              "userId": "agent-1"
            }
            """;

        HttpRequest executeReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/demo/db-action-registry/execute/ticket.lookup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(executeBody))
                .build();
        HttpResponse<String> executeRes = httpClient.send(executeReq, HttpResponse.BodyHandlers.ofString());
        assertThat(executeRes.statusCode()).isEqualTo(200);

        JsonNode executeJson = objectMapper.readTree(executeRes.body());
        assertThat(executeJson.get("success").asBoolean()).isTrue();
        assertThat(executeJson.get("data").get("ticketId").asText()).isEqualTo("TCK-1001");
    }
}