package com.multiship.backend.dto;

import com.multiship.backend.model.CustomFieldDefinition;
import com.multiship.backend.model.CustomFieldDefinition.FieldType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Sprint 43 — DTO for the custom-field definition CRUD. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomFieldDefinitionDTO {

    private Long id;
    private String tenantId;
    private String fieldKey;
    private String label;
    private FieldType fieldType;
    private String selectOptions;
    private Boolean required;
    private Integer position;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CustomFieldDefinitionDTO from(CustomFieldDefinition d) {
        return CustomFieldDefinitionDTO.builder()
                .id(d.getId())
                .tenantId(d.getTenantId())
                .fieldKey(d.getFieldKey())
                .label(d.getLabel())
                .fieldType(d.getFieldType())
                .selectOptions(d.getSelectOptions())
                .required(d.getRequired())
                .position(d.getPosition())
                .active(d.getActive())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }

    public CustomFieldDefinition toEntity() {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setId(id);
        d.setTenantId(tenantId);
        d.setFieldKey(fieldKey);
        d.setLabel(label);
        d.setFieldType(fieldType == null ? FieldType.TEXT : fieldType);
        d.setSelectOptions(selectOptions);
        d.setRequired(required != null && required);
        d.setPosition(position == null ? 100 : position);
        d.setActive(active == null ? Boolean.TRUE : active);
        return d;
    }
}
