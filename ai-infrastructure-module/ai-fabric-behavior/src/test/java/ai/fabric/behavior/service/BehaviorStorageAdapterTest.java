package ai.fabric.behavior.service;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.spi.BehaviorInsightStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BehaviorStorageAdapterTest {

    @Test
    void routesToCustomStoreWhenPresent() {
        BehaviorInsightStore customStore = mock(BehaviorInsightStore.class);
        BehaviorInsightsRepository repo = mock(BehaviorInsightsRepository.class);
        BehaviorStorageAdapter adapter = new BehaviorStorageAdapter(Optional.of(customStore), repo);

        String userId = "user-12345";
        BehaviorInsights insight = BehaviorInsights.builder()
            .userId(userId)
            .segment("custom")
            .analyzedAt(LocalDateTime.now())
            .build();

        when(customStore.findByUserId(userId)).thenReturn(Optional.of(insight));

        assertThat(adapter.findByUserId(userId)).contains(insight);
        adapter.save(insight);
        adapter.deleteByUserId(userId);

        verify(customStore).findByUserId(userId);
        verify(customStore).save(insight);
        verify(customStore).deleteByUserId(userId);
        verifyNoInteractions(repo);
    }

    @Test
    void fallsBackToRepositoryWhenNoCustomStore() {
        BehaviorInsightsRepository repo = mock(BehaviorInsightsRepository.class);
        BehaviorStorageAdapter adapter = new BehaviorStorageAdapter(Optional.empty(), repo);

        String userId = "user-67890";
        BehaviorInsights insight = BehaviorInsights.builder()
            .userId(userId)
            .segment("repo")
            .analyzedAt(LocalDateTime.now())
            .build();

        when(repo.findByUserId(userId)).thenReturn(Optional.of(insight));
        when(repo.save(insight)).thenReturn(insight);

        assertThat(adapter.findByUserId(userId)).contains(insight);
        assertThat(adapter.save(insight)).isSameAs(insight);
        adapter.deleteByUserId(userId);

        verify(repo).findByUserId(userId);
        verify(repo).save(insight);
        verify(repo).deleteByUserId(userId);
    }
}
