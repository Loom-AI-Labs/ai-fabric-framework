package com.ai.infrastructure.behavior.repository;

import com.ai.infrastructure.behavior.entity.BehaviorInsights;
import com.ai.infrastructure.behavior.model.BehaviorTrend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BehaviorInsightsRepository extends JpaRepository<BehaviorInsights, UUID> {
    
    Optional<BehaviorInsights> findByUserId(String userId);
    
    void deleteByUserId(String userId);

    List<BehaviorInsights> findByTrend(BehaviorTrend trend);

    List<BehaviorInsights> findBySentimentLabel(com.ai.infrastructure.behavior.model.SentimentLabel label);

    @Query("select b from BehaviorInsights b where b.trend = com.ai.infrastructure.behavior.model.BehaviorTrend.RAPIDLY_DECLINING order by b.updatedAt desc")
    List<BehaviorInsights> findRapidlyDecliningUsers();
}
