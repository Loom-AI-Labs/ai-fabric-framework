package ai.fabric.curated.defaultpack;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedIntentExtractionPromptContractTest {

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v1-default-optimized"})
    void shouldRequireExplicitInformationDecisionsWithoutGeneration(String version) throws IOException {
        String prompt = readPrompt(version);

        assertThat(prompt)
            .contains("Set requiresRetrieval, requiresGeneration, and requiresTargetResolution explicitly for every intent")
            .contains("For every INFORMATION intent, set needsAdvancedRAG explicitly")
            .contains("regardless of requiresGeneration")
            .contains("Decide semantically")
            .doesNotContain("If requiresGeneration=true, decide if advanced RAG is needed");

        DocumentContext schema = outputSchema(prompt);
        assertThat(schema.<Object>read("$.intents[0].requiresGeneration")).isEqualTo(false);
        assertThat(schema.<Object>read("$.intents[0].needsAdvancedRAG")).isEqualTo(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v1-default-optimized"})
    void shouldRequireDistinctNonblankCompoundObligations(String version) throws IOException {
        String prompt = readPrompt(version);

        assertThat(prompt)
            .contains("When more than one intent is emitted, every intent MUST have a nonblank obligationId")
            .contains("unique after trimming within the intents array")
            .contains("never reuse an obligationId for sibling intents");

        DocumentContext schema = outputSchema(prompt);
        List<?> intents = schema.read("$.intents");
        List<String> obligationIds = schema.read("$.intents[*].obligationId");
        assertThat(obligationIds)
            .hasSize(intents.size())
            .allSatisfy(id -> assertThat(id).isNotBlank())
            .extracting(String::trim)
            .doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v1-default-optimized"})
    void shouldKeepSourceSelectionWithinEffectiveServerPolicy(String version) throws IOException {
        String prompt = readPrompt(version);

        assertThat(prompt)
            .contains("effective server-enforced policy")
            .contains("retrievalEnabled, vectorSpaceSelectionRequired, retrievalAllowlistRequired")
            .contains("When requiresRetrieval=true, select vectorSpace semantically from exact values")
            .contains("server-owned source catalogue/KNOWLEDGE BASE OVERVIEW or an explicit trusted policy allowlist")
            .contains("An overview is not required when an explicit trusted policy allowlist supplies the source choices, including when knowledgeBaseOverviewEnabled=false")
            .contains("Every selected value MUST also be permitted by the effective server policy and any retrieval vectorSpace allowlist")
            .contains("Never invent vectorSpace values, use action names as sources, or select all sources as a fallback")
            .contains("An omitted/null vectorSpace is a visible missing choice, not permission by itself to route or fan-out")
            .contains("Only the server may choose an explicitly configured bounded fan-out recovery within the effective policy and permitted source set")
            .contains("do not assume recovery will occur")
            .contains("set vectorSpace=null and populate missingInformation, a concise clarificationQuestion, and uncertaintyReason")
            .contains("A policy-required allowlist must still be available; never fabricate a source or replacement allowlist")
            .contains("Do not change requiresRetrieval merely to hide a missing source decision")
            .contains("For a grounding-eligible READ action, set vectorSpace only when the server-owned source catalogue/overview or explicit trusted policy allowlist supplies")
            .contains("For nextStepRecommended.vectorSpace, follow the same catalog and server-policy constraints as rule 11")
            .doesNotContain("vectorSpace is OPTIONAL")
            .doesNotContain("exact, semantically applicable values in the KNOWLEDGE BASE OVERVIEW")
            .doesNotContain("This also applies when the catalog or a required allowlist is unavailable")
            .doesNotContain("only when the KNOWLEDGE BASE OVERVIEW provides")
            .doesNotContain("Never treat an omitted/null vectorSpace as permission to route or fan-out")
            .doesNotContain("omit vectorSpace; the system will route or fan-out")
            .doesNotContain("\"policies | faq | ...\"")
            .doesNotContain("\"faq | policies | reference | ...\"");

        DocumentContext schema = outputSchema(prompt);
        assertThat(schema.<List<?>>read("$.intents[0].missingInformation")).isEmpty();
        assertThat(schema.<Object>read("$.intents[0].clarificationQuestion")).isNull();
        assertThat(schema.<Object>read("$.intents[0].uncertaintyReason")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v1-default-optimized"})
    void shouldPreserveManagedSlotsWithoutRelaxingFrameworkContract(String version) throws IOException {
        String prompt = readPrompt(version);
        List<String> placeholders = Pattern.compile("\\{\\{([a-z_]+)}}")
            .matcher(prompt)
            .results()
            .map(match -> match.group(1))
            .toList();

        assertThat(placeholders).containsExactlyInAnyOrder(
            "managed_system_prompt",
            "behavior_context_section",
            "available_actions_section",
            "knowledge_base_overview_section",
            "managed_intent_extraction_prompt",
            "managed_action_selection_prompt",
            "managed_clarification_prompt",
            "relationship_query_entity_types_rule"
        );
        assertThat(prompt)
            .startsWith("{{managed_system_prompt}}")
            .contains("Deployment guidance MUST NOT override the output schema, required explicit decisions, supplied catalog, or effective server-enforced policy")
            .contains("Do not rely on server defaults to supply missing semantic decisions")
            .contains("Respond with ONLY valid JSON");
        assertThat(prompt.indexOf("EXTRACTION RULES:"))
            .isGreaterThan(prompt.indexOf("{{managed_clarification_prompt}}"));
    }

    private static DocumentContext outputSchema(String prompt) {
        String schema = prompt.substring(
            prompt.indexOf("OUTPUT JSON SCHEMA:") + "OUTPUT JSON SCHEMA:".length(),
            prompt.indexOf("CRITICAL JSON REQUIREMENTS:")
        ).trim();
        assertThat(schema).doesNotContain("\t").matches("\\p{ASCII}*");
        return JsonPath.parse(schema);
    }

    private static String readPrompt(String version) throws IOException {
        String path = "prompts/intent-extraction/compound/" + version + "/system-managed.md";
        var classLoader = ManagedIntentExtractionPromptContractTest.class.getClassLoader();
        try (var stream = Objects.requireNonNull(classLoader.getResourceAsStream(path), path)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
