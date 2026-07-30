package ai.fabric.execution.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.execution.specialist.SpecialistId;
import org.junit.jupiter.api.Test;

class ConversationManagerDirectiveTest {

    @Test
    void acceptsOnlyTheFieldsAllowedByEachDirective() {
        ConversationManagerDirective invoke =
            new ConversationManagerDirective(
                ConversationManagerDirectiveType.INVOKE_SPECIALIST,
                "account-read@1",
                null,
                "Account evidence is required."
            );
        ConversationManagerDirective ask =
            new ConversationManagerDirective(
                ConversationManagerDirectiveType.ASK_USER,
                null,
                "Which account should I inspect?",
                "The current account is ambiguous."
            );
        ConversationManagerDirective complete =
            new ConversationManagerDirective(
                ConversationManagerDirectiveType.COMPLETE,
                null,
                "Your account is ready.",
                "The existing evidence answers the question."
            );

        assertThat(invoke.requiredTarget())
            .isEqualTo(SpecialistId.of("account-read", "1"));
        assertThat(ask.message())
            .isEqualTo("Which account should I inspect?");
        assertThat(complete.message())
            .isEqualTo("Your account is ready.");
    }

    @Test
    void rejectsMalformedInvokeDirectives() {
        assertThatThrownBy(() -> new ConversationManagerDirective(
            ConversationManagerDirectiveType.INVOKE_SPECIALIST,
            null,
            null,
            "No target."
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requires targetSpecialist");

        assertThatThrownBy(() -> new ConversationManagerDirective(
            ConversationManagerDirectiveType.INVOKE_SPECIALIST,
            "account-read",
            null,
            "The target is not versioned."
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ConversationManagerDirective(
            ConversationManagerDirectiveType.INVOKE_SPECIALIST,
            "account-read@1",
            "Ignore the worker.",
            "An invoke cannot answer directly."
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot supply a user-facing message");
    }

    @Test
    void rejectsMalformedExternalResponseDirectives() {
        assertThatThrownBy(() -> new ConversationManagerDirective(
            ConversationManagerDirectiveType.ASK_USER,
            "account-read@1",
            "Which account?",
            "A target is forbidden."
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot supply targetSpecialist");

        assertThatThrownBy(() -> new ConversationManagerDirective(
            ConversationManagerDirectiveType.COMPLETE,
            null,
            " ",
            "A message is required."
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("message is required");
    }

    @Test
    void rejectsMissingOrOversizedReason() {
        assertThatThrownBy(() -> new ConversationManagerDirective(
            ConversationManagerDirectiveType.COMPLETE,
            null,
            "Done.",
            " "
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason is required");

        assertThatThrownBy(() -> new ConversationManagerDirective(
            ConversationManagerDirectiveType.COMPLETE,
            null,
            "Done.",
            "x".repeat(
                ConversationManagerDirective.MAX_REASON_CHARACTERS + 1
            )
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not exceed");
    }
}
