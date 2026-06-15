package ai.fabric.behavior.repository;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.BehaviorTrend;
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

    List<BehaviorInsights> findBySentimentLabel(ai.fabric.behavior.model.SentimentLabel label);

    @Query("select b from BehaviorInsights b where b.trend = ai.fabric.behavior.model.BehaviorTrend.RAPIDLY_DECLINING order by b.updatedAt desc")
    List<BehaviorInsights> findRapidlyDecliningUsers();
}
