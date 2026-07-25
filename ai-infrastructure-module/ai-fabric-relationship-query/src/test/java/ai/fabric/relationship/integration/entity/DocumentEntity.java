package ai.fabric.relationship.integration.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDestination;
import jakarta.persistence.CascadeType;
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
@Table(name = "documents")
@AICapable(entityType = "document")
public class DocumentEntity {

    @Id
    @AIIdentity
    private String id;

    @AISearchable(priority = 100, required = true)
    @Column(nullable = false)
    private String title;

    @AIContext
    @Column(nullable = false)
    private String status;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "author_id")
    private UserEntity author;

    @AIContext(
        key = "author",
        destinations = AIContextDestination.API_RESPONSE
    )
    public String getAuthorName() {
        return author == null ? null : author.getFullName();
    }

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
