package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.chat.service.ChatSessionService;
import com.ai.fabric.realapps.agenticresolver.repository.AgenticResolverDemoSessionRepository;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class AgenticResolverSessionServiceTest {

    @Test
    void bindsPublicScenarioToServerOwnedSubject() {
        UUID readyUser = UUID.randomUUID();
        UUID paymentUser = UUID.randomUUID();
        AgenticResolverSessionService service = service(
            accountService(readyUser, paymentUser),
            new MutableClock(Instant.parse("2026-07-28T10:00:00Z")),
            10
        );

        AgenticResolverSessionService.SessionView created = service.create();

        assertThat(created.activeScenarioId()).isEqualTo("missing-payment");
        assertThat(created.scenarios())
            .extracting(AgenticResolverSessionService.ScenarioView::id)
            .containsExactly("ready-account", "missing-payment");
        assertThat(created.toString())
            .doesNotContain(readyUser.toString(), paymentUser.toString());
        assertThat(service.active(created.sessionId()).subjectUserId())
            .isEqualTo(paymentUser);

        service.select(created.sessionId(), "ready-account");
        assertThat(service.active(created.sessionId()).subjectUserId())
            .isEqualTo(readyUser);
    }

    @Test
    void expiresSessionsAndReleasesCapacity() {
        MutableClock clock = new MutableClock(
            Instant.parse("2026-07-28T10:00:00Z")
        );
        ChatSessionService chatSessions = mock(ChatSessionService.class);
        AgenticResolverSessionService service = service(
            accountService(UUID.randomUUID(), UUID.randomUUID()),
            clock,
            1,
            chatSessions
        );
        String first = service.create().sessionId();

        assertThatThrownBy(service::create)
            .isInstanceOf(
                AgenticResolverSessionService.SessionCapacityExceededException.class
            );

        clock.advance(Duration.ofHours(7));
        assertThat(service.cleanupExpired()).isEqualTo(1);
        assertThatThrownBy(() -> service.get(first))
            .isInstanceOf(NoSuchElementException.class);
        verify(chatSessions).deleteConversation(
            "agentic-chat:" + first + ":ready-account",
            "demo:" + first + ":ready-account"
        );
        verify(chatSessions).deleteConversation(
            "agentic-chat:" + first + ":missing-payment",
            "demo:" + first + ":missing-payment"
        );
        assertThat(service.create()).isNotNull();
    }

    @Test
    void excludesLegacyWriteScenarioAndPublishesAssessmentOnlyPrompts() {
        AccountResolutionService accountService =
            mock(AccountResolutionService.class);
        when(accountService.createDemoSession(isNull())).thenReturn(
            new AccountResolutionService.DemoSession(
                "seeded-session",
                List.of(
                    scenario("missing-payment", UUID.randomUUID()),
                    scenario("refund-request", UUID.randomUUID())
                ),
                Map.of()
            )
        );
        AgenticResolverSessionService service = service(
            accountService,
            new MutableClock(Instant.parse("2026-07-28T10:00:00Z")),
            10
        );

        AgenticResolverSessionService.SessionView created = service.create();

        assertThat(created.scenarios())
            .extracting(AgenticResolverSessionService.ScenarioView::id)
            .containsExactly("missing-payment");
        assertThat(created.scenarios().getFirst().suggestedPrompt())
            .contains("Review my current account profile")
            .doesNotContainIgnoringCase("add my", "refund", "update");
    }

    private AgenticResolverSessionService service(
        AccountResolutionService accountService,
        Clock clock,
        int capacity
    ) {
        return service(accountService, clock, capacity, null);
    }

    private AgenticResolverSessionService service(
        AccountResolutionService accountService,
        Clock clock,
        int capacity,
        ChatSessionService chatSessionService
    ) {
        return new AgenticResolverSessionService(
            accountService,
            clock,
            Duration.ofHours(6),
            capacity,
            provider(chatSessionService),
            sessionRepositoryProvider()
        );
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatSessionService> provider(
        ChatSessionService service
    ) {
        ObjectProvider<ChatSessionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<AgenticResolverDemoSessionRepository>
        sessionRepositoryProvider() {
        ObjectProvider<AgenticResolverDemoSessionRepository> provider =
            mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private AccountResolutionService accountService(
        UUID readyUser,
        UUID paymentUser
    ) {
        AccountResolutionService service = mock(AccountResolutionService.class);
        when(service.createDemoSession(isNull())).thenReturn(
            new AccountResolutionService.DemoSession(
                "seeded-session",
                List.of(
                    scenario("ready-account", readyUser),
                    scenario("missing-payment", paymentUser)
                ),
                Map.of()
            )
        );
        return service;
    }

    private AccountResolutionService.DemoResolverScenario scenario(
        String id,
        UUID userId
    ) {
        return new AccountResolutionService.DemoResolverScenario(
            id,
            userId.toString(),
            UUID.randomUUID(),
            91L,
            id,
            "Scenario " + id,
            "Inspect my account"
        );
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
