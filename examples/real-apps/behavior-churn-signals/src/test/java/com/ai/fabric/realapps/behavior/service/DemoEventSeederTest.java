package com.ai.fabric.realapps.behavior.service;

import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import com.ai.fabric.realapps.behavior.domain.AppBehaviorEvent;
import com.ai.fabric.realapps.behavior.repo.AppBehaviorEventRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoEventSeederTest {

    private final AppBehaviorEventRepository eventRepository = mock(AppBehaviorEventRepository.class);
    private final BehaviorInsightsRepository insightsRepository = mock(BehaviorInsightsRepository.class);
    private final DemoEventSeeder seeder = new DemoEventSeeder(eventRepository, insightsRepository);

    @Test
    void seedClearsPreviousInsightsAndCreatesReplayableDemoEvents() {
        when(eventRepository.count()).thenReturn(33L);

        long count = seeder.seed();

        assertThat(count).isEqualTo(33L);
        verify(insightsRepository).deleteAll();
        verify(eventRepository).deleteAll();
        verify(eventRepository, times(33)).save(any(AppBehaviorEvent.class));
    }

    @Test
    void seedScenarioReplacesOnlyTheRequestedUser() {
        long count = seeder.seedScenario("session-user-1004", "release-regression", "test-session", null);

        assertThat(count).isEqualTo(7L);
        verify(eventRepository).deleteByUserId("session-user-1004");
        verify(insightsRepository).deleteByUserId("session-user-1004");
        verify(eventRepository, times(7)).save(any(AppBehaviorEvent.class));
    }
}
