package com.ai.infrastructure.relationship.integration.entity;

import com.ai.infrastructure.annotation.AICapable;
import jakarta.persistence.CascadeType;
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
    private String id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String fullName;

    @OneToMany(mappedBy = "author", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DocumentEntity> documents = new ArrayList<>();

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
