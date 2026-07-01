package ai.fabric.curated.commerce;

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

class CommerceCuratedPackTest {

    @Test
    void shouldLoadCommercePackDefaultsAndTemplates() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "ai.curated.pack", "commerce"
        )));

        new CuratedPackEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication(Object.class));

        OrchestrationProperties props = Binder.get(environment)
            .bind("ai.orchestration", OrchestrationProperties.class)
            .orElseThrow(() -> new IllegalStateException("Failed to bind ai.orchestration"));

        assertThat(props.getProfile()).isEqualTo(OrchestrationProfile.PRODUCTION_CHAT);
        assertThat(props.isAlwaysGenerateInformation()).isTrue();
        assertThat(props.getModes()).containsKey("navigator");
        assertThat(props.getModes()).containsKey("navigator_deep");
        assertThat(props.getModes().get("navigator").getForceGroundingEligibleReadActionPostGeneration())
            .isTrue();
        assertThat(props.getModes().get("navigator_deep").getUseAdvancedRag()).isEqualTo(true);
        assertThat(props.getModes().get("navigator_deep").getForceGroundingEligibleReadActionPostGeneration())
            .isTrue();
        assertThat(props.getModes().get("navigator_deep").getReadActionResolution()).isNotNull();
        assertThat(props.getModes().get("navigator_deep").getReadActionResolution().getEnabled()).isTrue();
        assertThat(props.getModes().get("navigator_deep").getReadActionResolution().getAllowedReadActions())
            .contains(
                "commerce_search_catalog",
                "commerce_get_product_details",
                "commerce_search_policies",
                "commerce_get_cart",
                "commerce_get_customer_context_summary",
                "commerce_get_most_recent_order_status",
                "commerce_get_order_status",
                "commerce_get_store_credit_balances",
                "list_products",
                "search_products",
                "get_product_details",
                "check_availability",
                "get_policy",
                "view_cart"
            )
            .doesNotContain(
                "relationship_query",
                "find_similar_products",
                "compare_products"
            );
        assertThat(props.getModes().get("executor").getReadActionResolution()).isNotNull();
        assertThat(props.getModes().get("executor").getForceGroundingEligibleReadActionPostGeneration())
            .isTrue();
        assertThat(props.getModes().get("executor").getReadActionResolution().getAllowedReadActions())
            .contains(
                "commerce_get_cart",
                "commerce_get_customer_context_summary",
                "commerce_get_most_recent_order_status",
                "commerce_get_order_status",
                "commerce_get_store_credit_balances"
            );
        assertThat(props.getModes().get("cart_assistant").getReadActionResolution()).isNotNull();
        assertThat(props.getModes().get("cart_assistant").getForceGroundingEligibleReadActionPostGeneration())
            .isTrue();
        assertThat(props.getModes().get("cart_assistant").getReadActionResolution().getAllowedReadActions())
            .contains(
                "commerce_get_cart",
                "commerce_get_customer_context_summary",
                "commerce_get_most_recent_order_status",
                "commerce_get_order_status",
                "commerce_get_store_credit_balances"
            );
        assertThat(props.getModes()).containsKey("cart_assistant");
        assertThat(props.getModes()).containsKey("resolver_assistant");
        assertThat(props.getModes()).containsKey("thinker");
        assertThat(props.getModes().get("resolver_assistant").getForceGroundingEligibleReadActionPostGeneration())
            .isTrue();
        assertThat(props.getModes().get("resolver_assistant").getReadActionResolution()).isNotNull();
        assertThat(props.getModes().get("resolver_assistant").getReadActionResolution().getEnabled()).isTrue();
        assertThat(props.getModes().get("resolver_assistant").getReadActionResolution().getAllowedReadActions())
            .contains(
                "commerce_search_catalog",
                "commerce_get_product_details",
                "commerce_search_policies",
                "commerce_get_cart",
                "commerce_get_customer_context_summary",
                "commerce_get_most_recent_order_status",
                "commerce_get_order_status",
                "commerce_get_store_credit_balances",
                "list_products",
                "search_products",
                "get_product_details",
                "check_availability",
                "get_policy",
                "view_cart"
            )
            .doesNotContain(
                "relationship_query",
                "find_similar_products",
                "compare_products"
            );
        assertThat(props.getModes().get("thinker").getForceGroundingEligibleReadActionPostGeneration())
            .isTrue();
        assertThat(props.getModes().get("thinker").getReadActionResolution()).isNotNull();
        assertThat(props.getModes().get("thinker").getReadActionResolution().getPlanningMode())
            .isEqualTo(OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE);
        assertThat(props.getModes().get("thinker").getReadActionResolution().getAllowedReadActions())
            .contains(
                "commerce_search_catalog",
                "commerce_get_product_details",
                "commerce_search_policies",
                "commerce_get_cart",
                "commerce_get_customer_context_summary",
                "commerce_get_most_recent_order_status",
                "commerce_get_order_status",
                "commerce_get_store_credit_balances",
                "list_products",
                "search_products",
                "get_product_details",
                "check_availability",
                "get_policy",
                "view_cart"
            )
            .doesNotContain(
                "relationship_query",
                "find_similar_products",
                "compare_products"
            );

        assertThat(environment.getProperty("ai.prompts.bundle.overlays[0]")).isEqualTo("v1-commerce");
        PromptBundleProperties promptBundle = Binder.get(environment)
            .bind("ai.prompts.bundle", PromptBundleProperties.class)
            .orElseGet(PromptBundleProperties::new);

        PromptTemplateResolver resolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            promptBundle
        );
        var classifierTemplate = resolver.resolve("intent-extraction/multi-step", "classify").template();
        assertThat(classifierTemplate.key().version()).isEqualTo("v1-commerce");
        assertThat(classifierTemplate.template())
            .contains("asks about assistant implementation/infrastructure")
            .contains("Highest priority: if the USER REQUEST asks about assistant implementation")
            .contains("Never use directAnswer to discuss assistant implementation")
            .contains("answer only the shopper-safe capability or use OUT_OF_SCOPE")
            .contains("Do not retrieve or substitute another product")
            .contains("decide current-product identity from ATTACHMENTS/PINNED TARGETS only")
            .contains("I can help with this store's products, policies, comparisons, cart, and approved order help.")
            .contains("must not repeat or quote the unsupported topic/request");
        assertThat(resolver.resolve("intent-extraction/multi-step", "select-actions").template().template())
            .contains("Do not select a catalog/search READ action as the final action for a cart mutation")
            .contains("catalog evidence can be gathered later by the backend");
        assertThat(resolver.resolve("intent-extraction/multi-step", "fill-params").template().template())
            .contains("optional resolver-only product search parameter")
            .contains("Do not include cart verbs or action wording");
        assertThat(resolver.resolve("rag/generation", "answer-managed").template().template())
            .contains("Use only the relevant commerce context above")
            .contains("Highest priority: if the user question asks about assistant implementation")
            .contains("Do not repeat labels such as \"Current page\"")
            .contains("Do not treat retrieved catalog/search results as the current product")
            .contains("do not answer as a missing-evidence or product-data question")
            .contains("treat, cure, diagnose, or prevent a health condition")
            .contains("asks for professional advice, asks whether a product can treat")
            .contains("no safest option can be identified from the available live store data")
            .contains("state that it is not available in the live store data")
            .contains("treat the explicit availability fact as the stock-status source of truth")
            .contains("Do not recommend checking another website, contacting support, contacting a vendor/manufacturer")
            .contains("Do not add next-step or handoff sentences for missing live data")
            .contains("Do not substitute price, availability, vendor, or product title as safety evidence")
            .contains("present explicit price, availability, variant, shipping-policy, review-signal")
            .contains("Do not append generic closers");
    }

    @Test
    void shouldResolveEveryCommercePromptOverlay() throws Exception {
        PromptTemplateResolver resolver = commerceResolver();

        for (String promptResource : promptResources()) {
            String resource = promptResource.substring("prompts/".length());
            int versionStart = resource.indexOf("/v1-commerce/");
            assertThat(versionStart)
                .as(promptResource)
                .isPositive();

            String family = resource.substring(0, versionStart);
            String name = resource.substring(versionStart + "/v1-commerce/".length(), resource.length() - ".md".length());
            String rawTemplate = readResource(promptResource);

            var resolved = resolver.resolve(family, name).template();
            assertThat(resolved.key().version()).as(promptResource).isEqualTo("v1-commerce");
            assertThat(resolved.template()).as(promptResource).isEqualTo(rawTemplate).isNotBlank();
        }
    }

    private static PromptTemplateResolver commerceResolver() {
        PromptBundleProperties promptBundle = new PromptBundleProperties();
        promptBundle.setOverlays(List.of("v1-commerce"));
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            promptBundle
        );
    }

    private static String readResource(String resourcePath) throws IOException {
        var classLoader = CommerceCuratedPackTest.class.getClassLoader();
        try (var stream = Objects.requireNonNull(classLoader.getResourceAsStream(resourcePath), resourcePath)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> promptResources() throws IOException, URISyntaxException {
        var classLoader = CommerceCuratedPackTest.class.getClassLoader();
        var promptsUrl = Objects.requireNonNull(classLoader.getResource("prompts"), "prompts");
        Path promptsRoot = Path.of(promptsUrl.toURI());
        try (Stream<Path> paths = Files.walk(promptsRoot)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .map(promptsRoot::relativize)
                .map(path -> "prompts/" + path.toString().replace('\\', '/'))
                .filter(path -> path.contains("/v1-commerce/"))
                .sorted()
                .toList();
        }
    }
}
