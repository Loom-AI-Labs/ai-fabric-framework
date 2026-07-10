package ai.fabric.curated.support;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.curated.CuratedPackEnvironmentPostProcessor;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SupportCuratedPackTest {

    @Test
    void shouldLoadSupportPackDefaults() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "ai.curated.pack", "support"
        )));

        new CuratedPackEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication(Object.class));

        OrchestrationProperties props = Binder.get(environment)
            .bind("ai.orchestration", OrchestrationProperties.class)
            .orElseThrow(() -> new IllegalStateException("Failed to bind ai.orchestration"));

        assertThat(props.getProfile()).isEqualTo(OrchestrationProfile.PRODUCTION_CHAT);
        assertThat(props.isAlwaysGenerateInformation()).isTrue();
        assertThat(props.getDefaultMode()).isEqualTo("support_assistant");
        assertThat(props.getModes()).containsKeys("support_assistant", "support_deep", "support_operator");
        assertThat(props.getModes().get("support_assistant").getActionsPreferred()).isEqualTo(true);
        assertThat(props.getModes().get("support_deep").getDeepRetrievalEnabled()).isTrue();
        assertThat(props.getModes().get("support_deep").getRag()).isNotNull();
        assertThat(props.getModes().get("support_deep").getRag().getFanoutEnabled()).isTrue();
        assertThat(props.getModes().get("support_deep").getRag().getMaxSpaces()).isEqualTo(6);
        assertThat(props.getModes().get("support_operator").getKnowledgeBaseOverviewEnabled()).isFalse();
        assertThat(environment.getProperty("ai.prompts.bundle.overlays[0]")).isEqualTo("v1-support");
        assertThat(props.getPositionRouting())
            .containsEntry("support", "support_assistant")
            .containsEntry("troubleshooting", "support_deep")
            .containsEntry("operations", "support_operator");

        PromptBundleProperties promptBundle = Binder.get(environment)
            .bind("ai.prompts.bundle", PromptBundleProperties.class)
            .orElseGet(PromptBundleProperties::new);

        PromptTemplateResolver resolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            promptBundle
        );
        var classifierTemplate = resolver.resolve("intent-extraction/multi-step", "classify").template();
        assertThat(classifierTemplate.key().version()).isEqualTo("v1-support");
        assertThat(classifierTemplate.template())
            .contains("Support scope includes product or service help")
            .contains("Recent conversation follow-ups")
            .contains("Do not ask the user for internal identifiers")
            .contains("Policy, runbook, and known-issue documents are guidance");

        assertThat(resolver.resolve("rag/generation", "answer-managed").template().template())
            .contains("Use only the relevant support context above")
            .contains("Do not invent root cause")
            .contains("Do not ask for internal ids");
        assertThat(resolver.resolve("orchestration/read-action-resolution", "user").template().template())
            .contains("For support case state, ticket status")
            .contains("Do not ask the user for internal action parameters");
        assertThat(resolver.resolve("orchestration/post-action-generation", "user-generic-managed").template().template())
            .contains("If FACTS include live support state")
            .contains("Do not infer root cause");
    }

    @Test
    void shouldResolveEverySupportPromptOverlay() throws Exception {
        PromptTemplateResolver resolver = supportResolver();

        for (String promptResource : promptResources()) {
            String resource = promptResource.substring("prompts/".length());
            int versionStart = resource.indexOf("/v1-support/");
            assertThat(versionStart)
                .as(promptResource)
                .isPositive();

            String family = resource.substring(0, versionStart);
            String name = resource.substring(versionStart + "/v1-support/".length(), resource.length() - ".md".length());
            String rawTemplate = readResource(promptResource);

            var resolved = resolver.resolve(family, name).template();
            assertThat(resolved.key().version()).as(promptResource).isEqualTo("v1-support");
            assertThat(resolved.template()).as(promptResource).isEqualTo(rawTemplate).isNotBlank();
        }
    }

    private static PromptTemplateResolver supportResolver() {
        PromptBundleProperties promptBundle = new PromptBundleProperties();
        promptBundle.setOverlays(List.of("v1-support"));
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            promptBundle
        );
    }

    private static String readResource(String resourcePath) throws IOException {
        var classLoader = SupportCuratedPackTest.class.getClassLoader();
        try (var stream = Objects.requireNonNull(classLoader.getResourceAsStream(resourcePath), resourcePath)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> promptResources() throws IOException, URISyntaxException {
        var classLoader = SupportCuratedPackTest.class.getClassLoader();
        var promptsUrl = Objects.requireNonNull(classLoader.getResource("prompts"), "prompts");
        Path promptsRoot = Path.of(promptsUrl.toURI());
        try (Stream<Path> paths = Files.walk(promptsRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .map(promptsRoot::relativize)
                .map(path -> "prompts/" + path.toString().replace('\\', '/'))
                .filter(path -> path.contains("/v1-support/"))
                .sorted()
                .toList();
        }
    }
}
