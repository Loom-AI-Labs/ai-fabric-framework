package ai.fabric.relay.contract;

import ai.fabric.relay.AIFabricRelayApplication;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.mockmvc.MockMvcResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = AIFabricRelayApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "relay.auth.apiKey.enabled=true",
        "relay.auth.apiKey.value=test-relay-key",
        "relay.routing.mode=MAPPING"
    }
)
@AutoConfigureMockMvc
class RelayOpenApiContractTest {

    private static final String API_KEY_HEADER = "X-AIFABRIC-API-KEY";
    private static final String API_KEY_VALUE = "test-relay-key";

    private static OpenApiInteractionValidator openApiValidator;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void initOpenApiValidator() {
        Path spec = locateSpec("customer-connector-api.openapi.yml");
        // The relay OpenAPI contract spec is not distributed with this open-source
        // repository. Skip the contract test gracefully when it is absent so the build
        // stays green; it runs automatically wherever the spec is present on disk.
        Assumptions.assumeTrue(
            spec != null,
            "Relay OpenAPI spec (customer-connector-api.openapi.yml) not present; skipping contract test."
        );
        openApiValidator = OpenApiInteractionValidator
            .createForSpecificationUrl(spec.toUri().toString())
            .build();
    }

    @Test
    void actionsExecute_shouldConformToOpenApi_on200() throws Exception {
        MvcResult result = mockMvc.perform(post("/actions/execute")
                .header(API_KEY_HEADER, API_KEY_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "actionId": "ping",
                      "params": {},
                      "trace": {
                        "requestId": "req_1",
                        "conversationId": "c1",
                        "authContext": {
                          "subjectId": "customer_123",
                          "subjectType": "END_USER",
                          "authMode": "PUBLIC_RUNTIME_AUTHENTICATED",
                          "sessionId": "sess_1",
                          "issuer": "commerce-app",
                          "grantedScopes": ["chat:query", "chat:actions"],
                          "audiences": ["storefront-chat"]
                        }
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();

        assertResponseConforms("/actions/execute", Request.Method.POST, result);
    }

    @Test
    void actionsExecute_shouldConformToOpenApi_on401() throws Exception {
        MvcResult result = mockMvc.perform(post("/actions/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "actionId": "ping",
                      "params": {},
                      "trace": {
                        "requestId": "req_2",
                        "conversationId": "c2",
                        "authContext": {
                          "subjectId": "customer_456",
                          "subjectType": "END_USER",
                          "authMode": "PUBLIC_RUNTIME_AUTHENTICATED",
                          "sessionId": "sess_2",
                          "issuer": "commerce-app"
                        }
                      }
                    }
                    """))
            .andExpect(status().isUnauthorized())
            .andReturn();

        assertResponseConforms("/actions/execute", Request.Method.POST, result);
    }

    @Test
    void retrievalSearch_shouldConformToOpenApi_on200() throws Exception {
        MvcResult result = mockMvc.perform(post("/retrieval/search")
                .header(API_KEY_HEADER, API_KEY_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query": "return policy for AirPods Pro",
                      "vectorSpace": "policy",
                      "topK": 5,
                      "cursor": null,
                      "filters": { "locale": "en_US" },
                      "trace": {
                        "requestId": "req_3",
                        "conversationId": "c3",
                        "authContext": {
                          "subjectId": "anon_session_1",
                          "subjectType": "ANONYMOUS_SESSION",
                          "authMode": "PUBLIC_RUNTIME_ANONYMOUS",
                          "sessionId": "anon_session_1",
                          "issuer": "runtime-public-bootstrap",
                          "audiences": ["storefront-chat"]
                        }
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();

        assertResponseConforms("/retrieval/search", Request.Method.POST, result);
    }

    @Test
    void retrievalSearch_shouldConformToOpenApi_on401() throws Exception {
        MvcResult result = mockMvc.perform(post("/retrieval/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "query": "return policy for AirPods Pro",
                      "vectorSpace": "policy",
                      "topK": 5,
                      "trace": {
                        "requestId": "req_4",
                        "conversationId": "c4",
                        "authContext": {
                          "subjectId": "anon_session_2",
                          "subjectType": "ANONYMOUS_SESSION",
                          "authMode": "PUBLIC_RUNTIME_ANONYMOUS",
                          "sessionId": "anon_session_2",
                          "issuer": "runtime-public-bootstrap"
                        }
                      }
                    }
                    """))
            .andExpect(status().isUnauthorized())
            .andReturn();

        assertResponseConforms("/retrieval/search", Request.Method.POST, result);
    }

    private static void assertResponseConforms(String path, Request.Method method, MvcResult mvcResult) {
        ValidationReport report = openApiValidator.validateResponse(path, method, MockMvcResponse.of(mvcResult.getResponse()));
        assertThat(report.hasErrors())
            .withFailMessage(() -> "OpenAPI response validation failed:\n" + format(report))
            .isFalse();
    }

    private static String format(ValidationReport report) {
        if (report == null || report.getMessages() == null || report.getMessages().isEmpty()) {
            return "(no messages)";
        }
        return report.getMessages().stream()
            .map(msg -> "[" + msg.getLevel() + "] " + msg.getKey() + " - " + msg.getMessage())
            .collect(Collectors.joining("\n"));
    }

    private static Path locateSpec(String fileName) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 10 && dir != null; i++) {
            Path locatedByName = findByFileName(dir, fileName);
            if (locatedByName != null) {
                return locatedByName.normalize();
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static Path findByFileName(Path root, String fileName) {
        try (Stream<Path> stream = Files.find(
            root,
            4,
            (path, attrs) -> attrs.isRegularFile() && fileName.equals(path.getFileName().toString())
        )) {
            return stream.findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
