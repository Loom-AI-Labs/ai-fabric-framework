package ai.fabric.chat.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionAutoConfigurationPackagesTest {

    @Test
    void autoConfigurationImportsRegistersChatSessionPackageConfiguration() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(input).isNotNull();
            String imports = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(imports)
                .contains(ChatSessionAutoConfigurationPackages.class.getName())
                .contains(ChatSessionAutoConfiguration.class.getName());
            assertThat(ChatSessionAutoConfigurationPackages.BASE_PACKAGE).isEqualTo("ai.fabric.chat");
        }
    }
}
