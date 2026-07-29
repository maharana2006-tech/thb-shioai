package com.multiship.backend.dto;

import com.multiship.backend.model.ScheduledReport;
import com.multiship.backend.model.ScheduledReport.Dataset;
import com.multiship.backend.model.ScheduledReport.DeliveryType;
import com.multiship.backend.model.ScheduledReport.Frequency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Sprint 45 — CRUD DTO for scheduled reports. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledReportDTO {
    private Long id;
    private String tenantId;
    private String name;
    private Dataset dataset;
    private Frequency frequency;
    private String filtersJson;
    private DeliveryType deliveryType;
    private String deliveryEmail;
    private String deliveryWebhookUrl;
    private Boolean active;
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    /** G6 — read-only in DTO round-trip; stamped at CREATE by the controller. */
    private String createdBy;
    private String createdByRole;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ScheduledReportDTO from(ScheduledReport s) {
        return ScheduledReportDTO.builder()
                .id(s.getId()).tenantId(s.getTenantId()).name(s.getName())
                .dataset(s.getDataset()).frequency(s.getFrequency())
                .filtersJson(s.getFiltersJson())
                .deliveryType(s.getDeliveryType())
                .deliveryEmail(s.getDeliveryEmail())
                .deliveryWebhookUrl(s.getDeliveryWebhookUrl())
                .active(s.getActive())
                .nextRunAt(s.getNextRunAt())
                .lastRunAt(s.getLastRunAt())
                .createdBy(s.getCreatedBy())
                .createdByRole(s.getCreatedByRole())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build();
    }

    public ScheduledReport toEntity() {
        ScheduledReport s = new ScheduledReport();
        s.setId(id);
        s.setTenantId(tenantId != null && tenantId.isBlank() ? null : tenantId);
        s.setName(name);
        s.setDataset(dataset == null ? Dataset.ORDERS : dataset);
        s.setFrequency(frequency == null ? Frequency.DAILY : frequency);
        s.setFiltersJson(filtersJson);
        s.setDeliveryType(deliveryType == null ? DeliveryType.DASHBOARD : deliveryType);
        s.setDeliveryEmail(deliveryEmail);
        s.setDeliveryWebhookUrl(deliveryWebhookUrl);
        s.setActive(active == null ? Boolean.TRUE : active);
        return s;
    }
}
