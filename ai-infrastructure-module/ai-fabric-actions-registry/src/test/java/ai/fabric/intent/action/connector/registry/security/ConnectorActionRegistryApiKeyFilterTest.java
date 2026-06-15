package ai.fabric.intent.action.connector.registry.security;

import ai.fabric.intent.action.connector.registry.AIActionDbRegistryProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorActionRegistryApiKeyFilterTest {

    @Test
    void doFilterInternal_rejectsMissingApiKey() throws Exception {
        AIActionDbRegistryProperties props = new AIActionDbRegistryProperties();
        props.setEnabled(true);
        props.getApiKey().setEnabled(true);
        props.getApiKey().setValue("k1");

        ConnectorActionRegistryApiKeyFilter filter = new ConnectorActionRegistryApiKeyFilter(props);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/actions/registry");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void doFilterInternal_allowsValidApiKey() throws Exception {
        AIActionDbRegistryProperties props = new AIActionDbRegistryProperties();
        props.setEnabled(true);
        props.getApiKey().setEnabled(true);
        props.getApiKey().setValue("k1");

        ConnectorActionRegistryApiKeyFilter filter = new ConnectorActionRegistryApiKeyFilter(props);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/actions/registry");
        request.addHeader("X-AIFABRIC-API-KEY", "k1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doFilterInternal_doesNotFilterOtherPaths() throws Exception {
        AIActionDbRegistryProperties props = new AIActionDbRegistryProperties();
        props.setEnabled(true);
        props.getApiKey().setEnabled(true);
        props.getApiKey().setValue("k1");

        ConnectorActionRegistryApiKeyFilter filter = new ConnectorActionRegistryApiKeyFilter(props);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/profiles");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static final class RecordingChain implements FilterChain {
        private boolean called = false;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            called = true;
        }
    }
}

