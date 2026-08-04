package com.ai.fabric.realapps.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ai.fabric.realapps.mcpserver.config.McpApiKeyFilter;
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
