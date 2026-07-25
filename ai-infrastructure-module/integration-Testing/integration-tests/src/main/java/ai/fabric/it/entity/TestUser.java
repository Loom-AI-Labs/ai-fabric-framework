package ai.fabric.it.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Test User Entity for AI Infrastructure Integration Tests
 *
 * This entity represents a user that can be processed by the AI infrastructure.
 * It includes personal information and preferences for testing AI analysis.
 *
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Entity
@Table(name = "test_users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@AICapable(entityType = "test-user")
public class TestUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @AIIdentity
    private Long id;

    @Column(nullable = false, length = 100)
    @AISearchable(name = "firstName", priority = 100, required = true)
    private String firstName;

    @Column(nullable = false, length = 100)
    @AISearchable(name = "lastName", priority = 100, required = true)
    private String lastName;

    @Column(unique = true, nullable = false, length = 255)
    @AISearchable(name = "email", priority = 60)
    @AIContext(
        key = "email",
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.API_RESPONSE
        }
    )
    private String email;

    @Column(columnDefinition = "TEXT")
    @AISearchable(name = "bio", priority = 70)
    private String bio;

    @Column
    @AIContext(
        key = "age",
        dataType = AIContextDataType.NUMBER,
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.API_RESPONSE
        }
    )
    private Integer age;

    @Column(length = 100)
    @AIContext(
        key = "location",
        destinations = {
            AIContextDestination.VECTOR_METADATA,
            AIContextDestination.API_RESPONSE
        }
    )
    private String location;

    @Column(length = 20)
    private String phoneNumber;

    @Column
    private LocalDate dateOfBirth;

    @Column
    private Boolean active;

    @Column
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Helper methods for testing
    public String getFullName() {
        return firstName + " " + lastName;
    }

    @AISearchable(name = "displayName", priority = 90)
    public String getDisplayName() {
        return getFullName() + " (" + email + ")";
    }

    public boolean isAdult() {
        return age != null && age >= 18;
    }
}
