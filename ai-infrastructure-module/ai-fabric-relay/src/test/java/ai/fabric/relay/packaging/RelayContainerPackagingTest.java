package ai.fabric.relay.packaging;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RelayContainerPackagingTest {

    @Test
    void dockerfileShouldReferenceCurrentRelayModuleAndArtifact() throws Exception {
        String dockerfile = Files.readString(moduleFile("Dockerfile"));

        assertThat(dockerfile).contains(
            "ai-infrastructure-module/ai-fabric-relay/pom.xml",
            "-pl ai-fabric-relay",
            "ai-infrastructure-module/ai-fabric-relay/target/ai-fabric-relay-*.jar"
        );
        assertThat(dockerfile).doesNotContain("ai-infrastructure-relay");
    }

    @Test
    void dockerComposeShouldUseCurrentRelayDockerfilePath() throws Exception {
        String compose = Files.readString(moduleFile("docker-compose.yml"));

        assertThat(compose).contains("dockerfile: ai-infrastructure-module/ai-fabric-relay/Dockerfile");
        assertThat(compose).doesNotContain("ai-infrastructure-relay");
    }

    private Path moduleFile(String name) {
        Path direct = Path.of(name);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("ai-infrastructure-module", "ai-fabric-relay", name);
    }
}
