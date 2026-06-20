package ai.fabric.entity;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.connector.ConnectorActionDefinition;
import ai.fabric.intent.action.connector.ConnectorActionParamDefinition;
import ai.fabric.intent.action.ActionResultPresentationHint;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_connector_action")
public class RegisteredConnectorAction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "name", nullable = false, unique = true, length = 128)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Column(name = "category", length = 128)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false, length = 32)
    private ActionAccessMode accessMode;

    @Column(name = "requires_confirmation", nullable = false)
    private boolean requiresConfirmation;

    @Column(name = "confirmation_message", length = 1024)
    private String confirmationMessage;

    @Column(name = "anonymous_allowed", nullable = false)
    private boolean anonymousAllowed;

    @OneToMany(mappedBy = "action", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sortOrder ASC")
    private List<RegisteredConnectorActionParam> params = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static RegisteredConnectorAction fromDefinition(ConnectorActionDefinition definition) {
        RegisteredConnectorAction entity = new RegisteredConnectorAction();
        entity.name = definition != null && StringUtils.hasText(definition.name()) ? definition.name().trim() : null;
        entity.description = definition != null ? definition.description() : null;
        entity.category = definition != null ? definition.category() : null;
        entity.accessMode = definition != null ? definition.accessMode() : null;
        entity.requiresConfirmation = definition != null && definition.requiresConfirmation();
        entity.confirmationMessage = definition != null ? definition.confirmationMessage() : null;
        entity.anonymousAllowed = definition != null && definition.anonymousAllowed();

        entity.params.clear();
        List<ConnectorActionParamDefinition> params = definition != null ? definition.params() : List.of();
        int idx = 0;
        for (ConnectorActionParamDefinition param : params) {
            if (param == null) {
                continue;
            }
            RegisteredConnectorActionParam p = RegisteredConnectorActionParam.fromDefinition(param, idx++);
            p.setAction(entity);
            entity.params.add(p);
        }
        return entity;
    }

    public ConnectorActionDefinition toDefinition() {
        List<ConnectorActionParamDefinition> paramDefs = params != null
            ? params.stream().map(RegisteredConnectorActionParam::toDefinition).toList()
            : List.of();
        return new ConnectorActionDefinition(
            name,
            name,
            description,
            category,
            accessMode,
            requiresConfirmation,
            confirmationMessage,
            paramDefs,
            anonymousAllowed,
            accessMode != null && accessMode.isGroundingEligibleByDefault(),
            false,
            accessMode == ActionAccessMode.WRITE_ONLY
                ? ActionResultPresentationHint.STATUS
                : ActionResultPresentationHint.DEFAULT,
            null,
            null,
            null,
            List.of(),
            null
        );
    }
}
