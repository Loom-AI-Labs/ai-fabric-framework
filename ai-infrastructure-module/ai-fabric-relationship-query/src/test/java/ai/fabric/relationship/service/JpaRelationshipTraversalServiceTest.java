package ai.fabric.relationship.service;

import ai.fabric.relationship.dto.JpqlQuery;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaRelationshipTraversalServiceTest {

    @Mock
    private EntityManager entityManager;
    @Mock
    private Query jpaQuery;
    @Mock
    private EntityManagerFactory entityManagerFactory;
    @Mock
    private PersistenceUnitUtil persistenceUnitUtil;

    private JpaRelationshipTraversalService service;

    @BeforeEach
    void setUp() {
        service = new JpaRelationshipTraversalService(entityManager);
    }

    @Test
    void shouldExecuteJpqlAndReturnIdentifiers() {
        stubJpaInfrastructure();

        Object entityOne = new Object();
        Object entityTwo = new Object();
        when(jpaQuery.getResultList()).thenReturn(List.of(entityOne, entityTwo));
        when(persistenceUnitUtil.getIdentifier(entityOne)).thenReturn(101L);
        when(persistenceUnitUtil.getIdentifier(entityTwo)).thenReturn("doc-202");

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .primaryEntityType("document")
            .build();

        JpqlQuery query = JpqlQuery.builder()
            .jpql("select d from Document d where d.status = :status")
            .parameters(Map.of("status", "ACTIVE"))
            .limit(25)
            .build();

        TraversalResult ids = service.traverse(plan, query);

        assertThat(ids.entityIds()).containsExactly("101", "doc-202");
        verify(jpaQuery).setParameter("status", "ACTIVE");
        verify(jpaQuery).setMaxResults(25);
    }

    @Test
    void shouldReturnEmptyWhenQueryMissing() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .primaryEntityType("document")
            .build();

        TraversalResult ids = service.traverse(plan, null);

        assertThat(ids.entityIds()).isEmpty();
        verify(entityManager, never()).createQuery(anyString());
    }

    @Test
    void shouldNotApplyLimitWhenNotProvided() {
        stubJpaInfrastructure();

        Object entity = new Object();
        when(jpaQuery.getResultList()).thenReturn(List.of(entity));
        when(persistenceUnitUtil.getIdentifier(entity)).thenReturn("doc-11");

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .primaryEntityType("document")
            .build();

        JpqlQuery query = JpqlQuery.builder()
            .jpql("select d from Document d")
            .parameters(Map.of())
            .build();

        TraversalResult ids = service.traverse(plan, query);

        assertThat(ids.entityIds()).containsExactly("doc-11");
        verify(jpaQuery, never()).setMaxResults(anyInt());
    }

    private void stubJpaInfrastructure() {
        when(entityManager.createQuery(anyString())).thenReturn(jpaQuery);
        when(entityManager.getEntityManagerFactory()).thenReturn(entityManagerFactory);
        when(entityManagerFactory.getPersistenceUnitUtil()).thenReturn(persistenceUnitUtil);
    }
}
