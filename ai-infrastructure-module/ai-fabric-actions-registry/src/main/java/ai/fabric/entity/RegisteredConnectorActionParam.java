package ai.fabric.entity;

import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.connector.ConnectorActionParamDefinition;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_connector_action_param")
public class RegisteredConnectorActionParam {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "action_id", nullable = false)
    private RegisteredConnectorAction action;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", length = 1024)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 64)
    private AIActionParamType type;

    @Column(name = "required", nullable = false)
    private boolean required;

    @Column(name = "batch_targets", nullable = false)
    private boolean batchTargets;

    @Column(name = "pattern", length = 512)
    private String pattern;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ai_connector_action_param_allowed_values", joinColumns = @JoinColumn(name = "param_id"))
    @Column(name = "value", length = 256)
    private List<String> allowedValues = new ArrayList<>();

    @Column(name = "min_value")
    private Long min;

    @Column(name = "max_value")
    private Long max;

    @Column(name = "default_value", length = 512)
    private String defaultValue;

    @Column(name = "sensitive", nullable = false)
    private boolean sensitive;

    public static RegisteredConnectorActionParam fromDefinition(ConnectorActionParamDefinition definition, int sortOrder) {
        RegisteredConnectorActionParam entity = new RegisteredConnectorActionParam();
        entity.sortOrder = sortOrder;
        entity.name = definition != null && StringUtils.hasText(definition.name()) ? definition.name().trim() : null;
        entity.description = definition != null ? definition.description() : null;
        entity.type = definition != null ? definition.type() : null;
        entity.required = definition != null && definition.required();
        entity.batchTargets = definition != null && definition.batchTargets();
        entity.pattern = definition != null ? definition.pattern() : null;
        entity.allowedValues = definition != null && definition.allowedValues() != null ? new ArrayList<>(definition.allowedValues()) : new ArrayList<>();
        entity.min = definition != null ? definition.min() : null;
        entity.max = definition != null ? definition.max() : null;
        entity.defaultValue = definition != null && definition.defaultValue() != null
            ? Objects.toString(definition.defaultValue(), null)
            : null;
        entity.sensitive = definition != null && definition.sensitive();
        return entity;
    }

    public ConnectorActionParamDefinition toDefinition() {
        return new ConnectorActionParamDefinition(
            name,
            description,
            type,
            required,
            batchTargets,
            pattern,
            allowedValues != null ? List.copyOf(allowedValues) : List.of(),
            min,
            max,
            defaultValue,
            null,
            null,
            Map.of(),
            sensitive,
            null,
            Map.of(),
            List.of(),
            false,
            List.of(),
            null
        );
    }
}
