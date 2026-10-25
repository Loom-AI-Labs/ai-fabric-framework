package ai.fabric.intent.orchestration.information;

import ai.fabric.config.PromptBundleProperties;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadActionPlannerPromptOverlayTest {

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v1-default-optimized"})
    void shouldRenderSuppliedCatalogueWithoutChangingQueryOrBudgets(String version) {
        PromptBundleProperties properties = new PromptBundleProperties();
        if (!"v1".equals(version)) {
            properties.setOverlays(List.of(version));
        }
        PromptTemplateResolver resolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            properties
        );
        PromptRenderer renderer = new PromptRenderer();
        var resolved = resolver.resolve("orchestration/read-action-resolution", "user");

        String query = "Compare records R-42 and R-43, then explain their retention policy.";
        String intent = "{\"type\":\"INFORMATION\",\"requiresRetrieval\":false}";
        String catalogue = """
            {"retrievalEnabled":true,"vectorSpaces":["approved-records","approved-policy"],"selectionRequired":true,"allowlistRequired":true,"fanoutEnabled":false,"maxSpaces":1,"missingVectorSpaceRecovery":"LLM_REPAIR_THEN_CLARIFY"}
            """.strip();
        String actions = "[{\"name\":\"list_records\",\"requiredParameters\":[]}]";
        String priorEvidence = "[{\"action\":\"list_records\",\"success\":true}]";
        Map<String, String> values = Map.ofEntries(
            Map.entry("mode", "resolver"),
            Map.entry("query", query),
            Map.entry("intent_json", intent),
            Map.entry("knowledge_sources_json", catalogue),
            Map.entry("eligible_actions_json", actions),
            Map.entry("prior_evidence_json", priorEvidence),
            Map.entry("iteration", "2"),
            Map.entry("max_iterations", "4"),
            Map.entry("max_actions_per_iteration", "3"),
            Map.entry("max_total_actions", "7"),
            Map.entry("rag_cooperation_mode", "RAG_IF_ACTIONS_INSUFFICIENT")
        );

        assertThat(resolved.template().key().version()).isEqualTo(version);
        assertThat(renderer.requiredPlaceholders(resolved.template().template()))
            .containsExactlyInAnyOrderElementsOf(values.keySet());

        String rendered = renderer.render(resolved.template(), values);

        assertThat(rendered)
            .contains("Request query:\n" + query + "\n\nResolved mode:\nresolver")
            .contains("Extracted intent JSON:\n" + intent)
            .contains("Authorized knowledge-source catalogue JSON (server-owned; names are retrieval choices, not evidence):\n"
                + catalogue + "\n\nEligible READ actions JSON:\n" + actions)
            .containsOnlyOnce(catalogue)
            .contains("Prior read-action evidence JSON:\n" + priorEvidence)
            .contains("""
                Planner budgets:
                - iteration: 2 / 4
                - max actions this iteration: 3
                - max total actions: 7
                - rag cooperation mode: RAG_IF_ACTIONS_INSUFFICIENT
                """)
            .contains("When indexed knowledge is needed, select its relevant vectorSpace name(s) from the authorized catalogue in suggestedVectorSpaces, even if extraction initially requested no retrieval. Do not repeat an absent source selection merely because it is absent from the extracted intent.")
            .contains("An empty catalogue or retrievalEnabled=false does not authorize invented sources. Never interpret omission as permission to search every source; respect the stated selection, fan-out and missing-source recovery policy.");
        assertThat(renderer.requiredPlaceholders(rendered)).isEmpty();
        if ("v1-default-optimized".equals(version)) {
            assertThat(rendered)
                .contains("For current-user/current-tenant/current-session resource reads")
                .contains("Never ask the user for internal action parameters");
        }
    }
}
