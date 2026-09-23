package com.ai.fabric.realapps.mcpops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class McpConnectorApiKeyFilterTest {

    @Test
    void rejectsInternalConnectorCallsWithoutTheConfiguredKey()
        throws Exception {
        McpConnectorApiKeyFilter filter = filter("connector-secret");
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            "/internal/mcp-connector/actions/execute"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void permitsTheExactConfiguredConnectorKey() throws Exception {
        McpConnectorApiKeyFilter filter = filter("connector-secret");
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            "/internal/mcp-connector/actions/execute"
        );
        request.addHeader(
            McpConnectorApiKeyFilter.HEADER,
            "connector-secret"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void failsStartupWhenTheConnectorKeyIsMissing() {
        McpConnectorApiKeyFilter filter = filter(" ");

        assertThatThrownBy(filter::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP_OPERATIONS_CONNECTOR_API_KEY");
    }

    private McpConnectorApiKeyFilter filter(String key) {
        McpConnectorApiKeyFilter filter = new McpConnectorApiKeyFilter();
        ReflectionTestUtils.setField(filter, "expectedKey", key);
        return filter;
    }
}
