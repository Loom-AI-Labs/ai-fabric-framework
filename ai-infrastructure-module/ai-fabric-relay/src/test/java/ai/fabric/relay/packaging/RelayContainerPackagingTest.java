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
        assertThat(dockerfile).contains("mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-relay -am package");
        assertThat(dockerfile).doesNotContain("-DskipTests");
        assertThat(dockerfile).doesNotContain("ai-infrastructure-relay");
    }

    @Test
    void pomShouldBuildExecutableSpringBootJar() throws Exception {
        String pom = Files.readString(moduleFile("pom.xml"));

        assertThat(pom).contains(
            "<artifactId>spring-boot-maven-plugin</artifactId>",
            "<skip>false</skip>",
            "<goal>repackage</goal>"
        );
    }

    @Test
    void dockerComposeShouldUseCurrentRelayDockerfilePath() throws Exception {
        String compose = Files.readString(moduleFile("docker-compose.yml"));

        assertThat(compose).contains("dockerfile: ai-infrastructure-module/ai-fabric-relay/Dockerfile");
        assertThat(compose).doesNotContain("ai-infrastructure-relay");
    }

    @Test
    void helmChartShouldShipWithCurrentRelayImageAndTemplates() throws Exception {
        String chart = Files.readString(moduleFile("deploy/helm/ai-fabric-relay/Chart.yaml"));
        String values = Files.readString(moduleFile("deploy/helm/ai-fabric-relay/values.yaml"));
        String deployment = Files.readString(moduleFile("deploy/helm/ai-fabric-relay/templates/deployment.yaml"));
        String service = Files.readString(moduleFile("deploy/helm/ai-fabric-relay/templates/service.yaml"));

        assertThat(chart).contains("name: ai-fabric-relay", "version: 0.2.1");
        assertThat(values).contains("repository: ai-fabric-relay", "springApplicationJson:");
        assertThat(deployment).contains("name: ai-fabric-relay", "image:", "SPRING_APPLICATION_JSON", "/actuator/health");
        assertThat(service).contains("kind: Service", "targetPort: http");
        assertThat(chart + values + deployment + service).doesNotContain("ai-infrastructure-relay");
    }

    private Path moduleFile(String name) {
        Path direct = Path.of(name);
        if (Files.exists(direct)) {
            return direct;
        }
        return Path.of("ai-infrastructure-module", "ai-fabric-relay", name);
    }
}
