package ai.fabric.starter;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StarterPomTest {

    @Test
    void starterDeclaresExpectedDefaultModules() throws Exception {
        Document document = parsePom();
        NodeList dependencies = document.getElementsByTagName("dependency");

        Set<String> aiFabricArtifacts = new LinkedHashSet<>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            if ("io.github.loom-ai-labs".equals(text(dependency, "groupId"))) {
                aiFabricArtifacts.add(text(dependency, "artifactId"));
            }
        }

        assertThat(aiFabricArtifacts)
            .containsExactly(
                "ai-fabric-core",
                "ai-fabric-pii",
                "ai-fabric-indexing"
            );
    }

    @Test
    void starterDescriptionMatchesBundledModules() throws Exception {
        Document document = parsePom();

        assertThat(document.getElementsByTagName("description").item(0).getTextContent())
            .contains("core")
            .contains("PII")
            .contains("indexing");
    }

    private Document parsePom() throws Exception {
        Path pom = Path.of("pom.xml");
        if (!Files.exists(pom)) {
            pom = Path.of("ai-infrastructure-module/ai-fabric-starter/pom.xml");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(pom.toFile());
    }

    private String text(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }
}
