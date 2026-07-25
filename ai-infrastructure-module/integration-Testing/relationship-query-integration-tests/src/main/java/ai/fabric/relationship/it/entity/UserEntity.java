package ai.fabric.relationship.it.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "users")
@AICapable(entityType = "user")
public class UserEntity {

    @Id
    @AIIdentity
    private String id;

    @AISearchable(required = true)
    @Column(nullable = false)
    private String fullName;

    @AIContext
    @Column(nullable = false)
    private String email;

    @OneToMany(mappedBy = "author")
    private List<DocumentEntity> documents = new ArrayList<>();

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
