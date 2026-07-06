package com.ai.fabric.realapps.behavior.repo;

import com.ai.fabric.realapps.behavior.domain.AppBehaviorEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface AppBehaviorEventRepository extends JpaRepository<AppBehaviorEvent, Long> {

    List<AppBehaviorEvent> findByUserIdOrderByEventTimestampAsc(String userId);

    void deleteByUserId(String userId);

    @Query("select e from AppBehaviorEvent e where e.userId = :userId and e.eventTimestamp >= :since and e.eventTimestamp <= :until order by e.eventTimestamp asc")
    List<AppBehaviorEvent> findWindow(@Param("userId") String userId,
                                      @Param("since") LocalDateTime since,
                                      @Param("until") LocalDateTime until);

    @Query("select distinct e.userId from AppBehaviorEvent e")
    List<String> findDistinctUserIds();

    @Query("select max(e.eventTimestamp) from AppBehaviorEvent e where e.userId = :userId")
    LocalDateTime findLatestTimestamp(@Param("userId") String userId);
}
