package ai.fabric.it;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.service.VectorManagementService;
import ai.fabric.spi.RAGProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "ai.vector-db.lucene.index-path=./data/test-lucene-index/rag-conversation-context",
    "ai.vector-db.lucene.similarity-threshold=0.0"
})
class RAGLongConversationContextIntegrationTest {

    private static final String ENTITY_TYPE = "ragconversation";
    private static final int CONTEXT_WINDOW = 2;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private RAGProvider ragProvider;

    @Autowired
    private AIEmbeddingService embeddingService;

    private final List<String> userPromptWindow = new ArrayList<>();

    @BeforeEach
    void setUp() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
        userPromptWindow.clear();
        seedProjectKnowledgeBase();
    }

    @AfterEach
    void tearDown() {
        vectorManagementService.clearVectorsByEntityType(ENTITY_TYPE);
    }

    @Test
    @DisplayName("Conversation retains relevant context across multiple turns while pruning older history")
    void ragConversationMaintainsAndPrunesContext() {
        Deque<Turn> history = new ArrayDeque<>();

        // Turn 1: initial question
        ConversationResult turnOne = performConversationTurn(
            "What's the timeline for the Hyperion initiative?",
            history
        );

        assertTrue(containsDocument(turnOne.response(), "hyperion_timeline"),
            "Timeline question should surface the timeline document");
        appendHistory(history, "user", "What's the timeline for the Hyperion initiative?", extractPrimaryAnswer(turnOne.response()));

        // Turn 2: pronoun-based follow-up
        ConversationResult turnTwo = performConversationTurn(
            "Which milestones are still outstanding for it?",
            history
        );

        assertTrue(containsDocument(turnTwo.response(), "hyperion_milestones"),
            "Milestone follow-up should surface milestone document");
        appendHistory(history, "user", "Which milestones are still outstanding for it?", extractPrimaryAnswer(turnTwo.response()));
        assertEquals(List.of("What's the timeline for the Hyperion initiative?"), turnTwo.windowHistory(),
            "Second turn should include initial question in history");

        // Turn 3: ensure context window prunes oldest entry
        ConversationResult turnThree = performConversationTurn(
            "Who is leading it now?",
            history
        );

        assertEquals(List.of(
            "What's the timeline for the Hyperion initiative?",
            "Which milestones are still outstanding for it?"
        ), turnThree.windowHistory(), "Third turn should see full history before pruning");

        assertTrue(containsDocument(turnThree.response(), "hyperion_leadership"),
            "Leadership question should retrieve leadership document");
        String leadershipContent = getDocumentContent(turnThree.response(), "hyperion_leadership");
        assertTrue(leadershipContent.toLowerCase().contains("director"),
            "Returned document should include leadership information");
        appendHistory(history, "user", "Who is leading it now?", extractPrimaryAnswer(turnThree.response()));

        // Turn 4: verify pruning removes oldest exchange
        ConversationResult turnFour = performConversationTurn(
            "Does that director also oversee the remaining milestones?",
            history
        );

        assertEquals(List.of(
            "Which milestones are still outstanding for it?",
            "Who is leading it now?"
        ), turnFour.windowHistory(), "Context window should keep only the two most recent turns");
        assertTrue(containsDocument(turnFour.response(), "hyperion_milestones"),
            "Follow-up should bring back milestone details with leadership context");
        appendHistory(history, "user", "Does that director also oversee the remaining milestones?", extractPrimaryAnswer(turnFour.response()));

        // Verify context window behaviour (should contain last two exchanges only)
        assertEquals(CONTEXT_WINDOW, history.size(), "Conversation history should be pruned to the configured window");
        List<String> remainingPrompts = new ArrayList<>(userPromptWindow);
        assertEquals(List.of(
            "Who is leading it now?",
            "Does that director also oversee the remaining milestones?"
        ), remainingPrompts, "Conversation history should retain only the most recent turns in order");
    }

    private boolean containsDocument(RAGResponse response, String documentId) {
        return response.getDocuments().stream()
            .anyMatch(doc -> documentId.equals(doc.getId()));
    }

    private ConversationResult performConversationTurn(String question, Deque<Turn> history) {
        String conversationalQuery = buildConversationalQuery(question, history);

        List<String> windowHistory = history.stream()
            .map(Turn::userMessage)
            .collect(Collectors.toList());

        RAGRequest request = RAGRequest.builder()
            .query(conversationalQuery)
            .entityType(ENTITY_TYPE)
            .limit(5)
            .threshold(0.0)
            .enableHybridSearch(true)
            .enableContextualSearch(true)
            .context(Map.of(
                "history", history.stream()
                    .map(turn -> Map.of(
                        "role", turn.role(),
                        "user", turn.userMessage(),
                        "assistant", turn.assistantResponse()
                    ))
                    .collect(Collectors.toList()),
                "windowSize", CONTEXT_WINDOW
            ))
            .metadata(Map.of("sessionId", "hyperion-convo"))
            .build();

        RAGResponse response = ragProvider.performRag(request);
        return new ConversationResult(response, windowHistory);
    }

    private String buildConversationalQuery(String question, Deque<Turn> history) {
        if (history.isEmpty()) {
            return question;
        }

        String summarizedHistory = history.stream()
            .map(turn -> "User: " + turn.userMessage() + " | Assistant: " + turn.assistantResponse())
            .collect(Collectors.joining(" \n "));

        return "Conversation history: " + summarizedHistory + " \n Current question: " + question;
    }

    private void appendHistory(Deque<Turn> history, String role, String userMessage, String assistantResponse) {
        history.addLast(new Turn(role, userMessage, assistantResponse));
        while (history.size() > CONTEXT_WINDOW) {
            history.removeFirst();
        }
        userPromptWindow.add(userMessage);
        if (userPromptWindow.size() > CONTEXT_WINDOW) {
            userPromptWindow.remove(0);
        }
    }

    private String extractPrimaryAnswer(RAGResponse response) {
        if (response.getDocuments() == null || response.getDocuments().isEmpty()) {
            return "";
        }
        return response.getDocuments().getFirst().getContent();
    }

    private String getDocumentContent(RAGResponse response, String documentId) {
        return response.getDocuments().stream()
            .filter(doc -> documentId.equals(doc.getId()))
            .map(RAGResponse.RAGDocument::getContent)
            .findFirst()
            .orElse("");
    }

    private void seedProjectKnowledgeBase() {
        store("hyperion_timeline",
            "Hyperion initiative timeline runs from Q1 to Q4 with alpha launch in May and beta in September.");
        store("hyperion_milestones",
            "Remaining Hyperion milestones include security hardening, customer pilot onboarding, and compliance review.");
        store("hyperion_leadership",
            "Hyperion is currently led by program director Amina Patel with engineering managed by Leo Vance.");
        store("hyperion_budget",
            "Hyperion budget highlights ongoing cloud costs and marketing campaigns.");
        store("other_project",
            "Orion project focuses on supply chain analytics and is unrelated to Hyperion.");
    }

    private void store(String entityId, String content) {
        vectorManagementService.storeVector(
            ENTITY_TYPE,
            entityId,
            content,
            embeddingService.generateEmbedding(AIEmbeddingRequest.builder().text(content).build()).getEmbedding(),
            Map.of("project", entityId.contains("hyperion") ? "hyperion" : "other")
        );
    }

    private record Turn(String role, String userMessage, String assistantResponse) {
    }

    private record ConversationResult(RAGResponse response, List<String> windowHistory) {
    }
}
