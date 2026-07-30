package ai.fabric.execution.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.specialist.SpecialistId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationManagerInputTest {

    @Test
    void ownsOnlyTheCurrentMessageContextAndApprovedTargets() {
        var context = new java.util.ArrayList<>(
            List.of(new ConversationManagerContextValue(
                "accountRef",
                "current"
            ))
        );
        ConversationManagerInput input = new ConversationManagerInput(
            "  Why is my account blocked?  ",
            context,
            List.of(new ConversationManagerTargetView(
                SpecialistId.of("account-read", "1").toString(),
                "Inspect the current account."
            ))
        );
        context.add(new ConversationManagerContextValue("other", "mutated"));

        assertThat(input.currentUserMessage())
            .isEqualTo("Why is my account blocked?");
        assertThat(input.applicationContext())
            .containsExactly(new ConversationManagerContextValue(
                "accountRef",
                "current"
            ));
        assertThat(input.approvedTargets()).hasSize(1);
    }

    @Test
    void defaultsNullContextToAnEmptyObject() {
        ConversationManagerInput input = new ConversationManagerInput(
            "Help me.",
            null,
            List.of(new ConversationManagerTargetView(
                SpecialistId.of("account-read", "1").toString(),
                "Inspect the current account."
            ))
        );

        assertThat(input.applicationContext()).isEmpty();
    }

    @Test
    void rejectsInvalidMessagesContextsAndTargetSets() {
        assertThatThrownBy(() -> new ConversationManagerInput(
            " ",
            null,
            List.of(new ConversationManagerTargetView(
                SpecialistId.of("account-read", "1").toString(),
                "Inspect the current account."
            ))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("currentUserMessage is required");

        assertThatThrownBy(() -> new ConversationManagerInput(
            "Help me.",
            List.of(
                new ConversationManagerContextValue("duplicate", "one"),
                new ConversationManagerContextValue("duplicate", "two")
            ),
            List.of(new ConversationManagerTargetView(
                SpecialistId.of("account-read", "1").toString(),
                "Inspect the current account."
            ))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate name");

        assertThatThrownBy(() -> new ConversationManagerInput(
            "Help me.",
            null,
            List.of()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("approvedTargets must not be empty");
    }
}
