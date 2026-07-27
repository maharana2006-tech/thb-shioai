package com.multiship.backend.dto;

import com.multiship.backend.model.LabelTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Sprint 42 — DTO for the tenant-branded label template CRUD. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabelTemplateDTO {

    private Long id;
    private String tenantId;
    private String templateType;
    private String logoBase64;
    private String primaryColor;
    private String headerText;
    private String footerText;
    private Boolean showItems;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static LabelTemplateDTO from(LabelTemplate t) {
        return LabelTemplateDTO.builder()
                .id(t.getId())
                .tenantId(t.getTenantId())
                .templateType(t.getTemplateType())
                .logoBase64(t.getLogoBase64())
                .primaryColor(t.getPrimaryColor())
                .headerText(t.getHeaderText())
                .footerText(t.getFooterText())
                .showItems(t.getShowItems())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    public LabelTemplate toEntity() {
        LabelTemplate t = new LabelTemplate();
        t.setId(id);
        t.setTenantId(tenantId);
        t.setTemplateType(templateType == null ? "PACKING_SLIP" : templateType);
        t.setLogoBase64(logoBase64);
        t.setPrimaryColor(primaryColor);
        t.setHeaderText(headerText);
        t.setFooterText(footerText);
        t.setShowItems(showItems == null ? Boolean.TRUE : showItems);
        return t;
    }
}
