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
    /** Convenience for list views — true when logoBase64 is non-blank.
     *  Populated on every DTO so the frontend can render a Has-logo
     *  column without inspecting the (potentially 200 KB) blob. */
    private Boolean hasLogo;
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
                .hasLogo(t.getLogoBase64() != null && !t.getLogoBase64().isBlank())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    /** Same as {@link #from(LabelTemplate)} but omits the logo blob —
     *  meant for list endpoints so a page of 25 templates doesn't ship
     *  25 × up-to-200KB of base64 across the wire. */
    public static LabelTemplateDTO summary(LabelTemplate t) {
        LabelTemplateDTO dto = from(t);
        dto.setLogoBase64(null);
        return dto;
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
