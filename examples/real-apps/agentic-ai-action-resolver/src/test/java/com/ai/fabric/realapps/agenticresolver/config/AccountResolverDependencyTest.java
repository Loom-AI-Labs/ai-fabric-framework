package com.ai.fabric.realapps.agenticresolver.config;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AccountResolverDependencyTest {

    @Test
    void includesRagModuleForPolicyGroundedResolverMode() throws Exception {
        Path pom = resolveModulePom();
        Document document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(pom.toFile());

        assertThat(artifactIds(document))
            .contains("ai-fabric-rag")
            .contains("ai-fabric-chat-session")
            .contains("ai-fabric-execution");
    }

    private static Path resolveModulePom() {
        if (Path.of("src/main/java/com/ai/fabric/realapps/agenticresolver").toFile().isDirectory()) {
            return Path.of("pom.xml");
        }
        return Path.of("examples/real-apps/agentic-ai-action-resolver/pom.xml");
    }

    private static Iterable<String> artifactIds(Document document) {
        var nodes = document.getElementsByTagName("artifactId");
        return IntStream.range(0, nodes.getLength())
            .mapToObj(index -> nodes.item(index).getTextContent())
            .toList();
    }
}
