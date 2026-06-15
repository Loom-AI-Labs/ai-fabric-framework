package ai.fabric.behavior.repository;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.SentimentLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SpringExtension.class)
@org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:behavior_repo;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DEFAULT_NULL_ORDERING=HIGH",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@org.springframework.context.annotation.Import(BehaviorInsightsRepositoryTest.TestConfig.class)
class BehaviorInsightsRepositoryTest {

    @Autowired
    private BehaviorInsightsRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void createTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS ai_behavior_insights (
              id UUID PRIMARY KEY,
              user_id VARCHAR(255) UNIQUE NOT NULL,
              segment VARCHAR(100),
              patterns CLOB,
              recommendations CLOB,
              insights CLOB,
              sentiment_score DOUBLE,
              sentiment_label VARCHAR(50),
              churn_risk DOUBLE,
              churn_reason TEXT,
              previous_sentiment_score DOUBLE,
              previous_churn_risk DOUBLE,
              trend VARCHAR(50),
              analyzed_at TIMESTAMP,
              confidence DOUBLE,
              ai_model_used VARCHAR(100),
              model_prompt_version VARCHAR(20),
              processing_time_ms BIGINT,
              created_at TIMESTAMP,
              updated_at TIMESTAMP
            )
            """);
    }

    @Test
    void findRapidlyDecliningUsersFiltersAndOrders() throws Exception {
        BehaviorInsights improving = repository.save(BehaviorInsights.builder()
            .userId("user-improving")
            .trend(BehaviorTrend.IMPROVING)
            .sentimentLabel(SentimentLabel.SATISFIED)
            .churnRisk(0.2)
            .analyzedAt(LocalDateTime.now().minusMinutes(10))
            .build());

        BehaviorInsights decliningOld = repository.save(BehaviorInsights.builder()
            .userId("user-declining-old")
            .trend(BehaviorTrend.RAPIDLY_DECLINING)
            .sentimentLabel(SentimentLabel.FRUSTRATED)
            .churnRisk(0.9)
            .analyzedAt(LocalDateTime.now().minusMinutes(5))
            .build());

        Thread.sleep(5);
        decliningOld.setChurnRisk(0.95); // trigger update to refresh updatedAt
        repository.save(decliningOld);

        Thread.sleep(5);
        BehaviorInsights decliningNew = repository.save(BehaviorInsights.builder()
            .userId("user-declining-new")
            .trend(BehaviorTrend.RAPIDLY_DECLINING)
            .sentimentLabel(SentimentLabel.CHURNING)
            .churnRisk(0.98)
            .analyzedAt(LocalDateTime.now())
            .build());

        List<BehaviorInsights> results = repository.findRapidlyDecliningUsers();

        assertThat(results).hasSize(2);
        assertThat(results).extracting(BehaviorInsights::getUserId)
            .containsExactlyInAnyOrder(decliningNew.getUserId(), decliningOld.getUserId());
        assertThat(results).allMatch(bi -> bi.getTrend() == BehaviorTrend.RAPIDLY_DECLINING);
        assertThat(results).noneMatch(bi -> bi.getUserId().equals(improving.getUserId()));
    }

    @Test
    void uniqueUserIdConstraintEnforced() {
        String userId = "user-unique-test";
        repository.saveAndFlush(BehaviorInsights.builder()
            .userId(userId)
            .trend(BehaviorTrend.STABLE)
            .analyzedAt(LocalDateTime.now())
            .build());

        assertThatThrownBy(() -> repository.saveAndFlush(BehaviorInsights.builder()
                .userId(userId)
                .trend(BehaviorTrend.DECLINING)
                .analyzedAt(LocalDateTime.now())
                .build()))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = BehaviorInsightsRepository.class)
    @org.springframework.boot.autoconfigure.domain.EntityScan(basePackageClasses = BehaviorInsights.class)
    static class TestConfig {
    }
}
