package ai.fabric.behavior.service;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.spi.BehaviorInsightStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorStorageAdapter {
    
    private final Optional<BehaviorInsightStore> customStore;
    private final BehaviorInsightsRepository defaultRepository;
    
    public Optional<BehaviorInsights> findByUserId(String userId) {
        if (customStore.isPresent()) {
            log.debug("Fetching from custom store: userId={}", userId);
            return customStore.get().findByUserId(userId);
        }
        return defaultRepository.findByUserId(userId);
    }
    
    public BehaviorInsights save(BehaviorInsights insight) {
        if (customStore.isPresent()) {
            log.debug("Saving to custom store: userId={}", insight.getUserId());
            customStore.get().save(insight);
            return insight;
        }
        return defaultRepository.save(insight);
    }
    
    public void deleteByUserId(String userId) {
        if (customStore.isPresent()) {
            log.info("Deleting from custom store: userId={}", userId);
            customStore.get().deleteByUserId(userId);
        } else {
            defaultRepository.deleteByUserId(userId);
        }
    }
}
