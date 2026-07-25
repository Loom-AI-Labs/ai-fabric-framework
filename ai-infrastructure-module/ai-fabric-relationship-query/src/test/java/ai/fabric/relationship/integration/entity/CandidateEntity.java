package ai.fabric.relationship.integration.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDestination;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "candidates")
@AICapable(entityType = "candidate")
public class CandidateEntity {

    @Id
    @AIIdentity
    private String id;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false)
    private String fullName;

    @AISearchable(priority = 80)
    @Column(nullable = false)
    private String location;

    @AIContext
    @Column(nullable = false)
    private String seniority;

    @AISearchable(priority = 90)
    @Column(nullable = false)
    private String primarySkill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recruiter_id")
    private RecruiterEntity recruiter;

    @AIContext(
        key = "recruiter",
        destinations = AIContextDestination.API_RESPONSE
    )
    public String getRecruiterName() {
        return recruiter == null ? null : recruiter.getFullName();
    }

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
