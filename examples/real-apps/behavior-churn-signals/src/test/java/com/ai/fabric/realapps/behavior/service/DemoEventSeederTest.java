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
        when(eventRepository.count()).thenReturn(17L);

        long count = seeder.seed();

        assertThat(count).isEqualTo(17L);
        verify(insightsRepository).deleteAll();
        verify(eventRepository).deleteAll();
        verify(eventRepository, times(17)).save(any(AppBehaviorEvent.class));
    }
}
