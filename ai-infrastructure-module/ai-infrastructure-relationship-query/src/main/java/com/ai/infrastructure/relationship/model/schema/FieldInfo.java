package com.ai.infrastructure.relationship.model.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata about a scalar field that can be used for filtering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldInfo {
    private String name;
    private String type;
    private boolean nullable;
    private boolean searchable;
}
