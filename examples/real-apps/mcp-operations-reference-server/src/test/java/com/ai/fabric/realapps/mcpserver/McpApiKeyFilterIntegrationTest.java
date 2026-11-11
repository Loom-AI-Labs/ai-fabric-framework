package com.ai.fabric.realapps.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.fabric.realapps.mcpserver.config.McpApiKeyFilter;
import ai.fabric.intent.action.ActionNativeSchemaSupport;
import ai.fabric.intent.action.ActionParameterContractValidator.ActionParameterContractException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:mcp-auth-test;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.mcp.api-key=test-mcp-key"
})
@AutoConfigureMockMvc
class McpApiKeyFilterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsMissingOrIncorrectProtocolAuthentication() throws Exception {
        mockMvc.perform(get("/mcp"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/mcp").header(
                McpApiKeyFilter.HEADER,
                "wrong-key"
            ))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void nativeValidationCoexistsWithTheRealMcpServerValidator() {
        Map<String, Object> schema = Map.of("properties", Map.of(
            "selection", Map.of("type", List.of("string", "null"))),
            "required", List.of("selection"), "additionalProperties", false);
        ActionNativeSchemaSupport.validateInput(schema, Collections.singletonMap("selection", null));
        assertThatThrownBy(() -> ActionNativeSchemaSupport.validateInput(schema, Map.of("selection", 4)))
            .isInstanceOf(ActionParameterContractException.class);
    }

    @Test
    void acceptsConfiguredAuthenticationAtTheProtocolBoundary()
        throws Exception {
        int status = mockMvc.perform(get("/mcp").header(
                McpApiKeyFilter.HEADER,
                "test-mcp-key"
            ))
            .andReturn()
            .getResponse()
            .getStatus();

        assertThat(status).isNotEqualTo(401);
    }
}
