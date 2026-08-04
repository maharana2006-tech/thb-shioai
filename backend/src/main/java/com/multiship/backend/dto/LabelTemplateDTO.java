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
    /** Phase 1 drag-drop editor payload. Opaque JSON blob (see the
     *  {@code TemplateLayout} TypeScript type). Null on legacy templates
     *  that predate the layout builder. */
    private String layoutJson;
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
                .layoutJson(t.getLayoutJson())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }

    /** Same as {@link #from(LabelTemplate)} but omits heavy blobs — meant
     *  for list endpoints so a page of 25 templates doesn't ship the full
     *  logo + layout payload for each row. */
    public static LabelTemplateDTO summary(LabelTemplate t) {
        LabelTemplateDTO dto = from(t);
        dto.setLogoBase64(null);
        dto.setLayoutJson(null);
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
        t.setLayoutJson(layoutJson);
        return t;
    }
}
