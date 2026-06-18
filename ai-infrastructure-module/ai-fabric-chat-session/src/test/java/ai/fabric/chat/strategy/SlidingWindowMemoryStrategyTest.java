package ai.fabric.chat.strategy;

import ai.fabric.chat.domain.ChatTurn;
import ai.fabric.dto.AIChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlidingWindowMemoryStrategyTest {

    private final SlidingWindowMemoryStrategy strategy = new SlidingWindowMemoryStrategy();

    @Test
    void pruneShouldReturnSnapshotOfLatestTurns() {
        List<ChatTurn> history = new ArrayList<>();
        history.add(turn("u1", "a1"));
        history.add(turn("u2", "a2"));
        history.add(turn("u3", "a3"));

        List<ChatTurn> pruned = strategy.prune(history, 2);
        history.add(turn("u4", "a4"));

        assertThat(pruned).extracting(ChatTurn::getUserQuery).containsExactly("u2", "u3");
        assertThatThrownBy(() -> pruned.add(turn("u5", "a5")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toMessagesShouldSkipBlankContentAndIncludeBoundedMetadataContext() {
        ChatTurn turn = turn("show me order 1", "Order 1 is ready");
        turn.setTurnMetadata(Map.of(
            "_action", "get_order",
            "_actionSuccess", true,
            "_actionRefs", Map.of("orderId", "1")
        ));

        List<AIChatMessage> messages = strategy.toMessages(List.of(
            ChatTurn.builder().userQuery(" ").aiResponse(null).build(),
            turn
        ));

        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(AIChatMessage::getContent)
            .containsExactly(
                "show me order 1",
                "Order 1 is ready\nAction Context: action=get_order; success=true; refs=orderId=1"
            );
    }

    private ChatTurn turn(String userQuery, String aiResponse) {
        return ChatTurn.builder()
            .userQuery(userQuery)
            .aiResponse(aiResponse)
            .build();
    }
}
