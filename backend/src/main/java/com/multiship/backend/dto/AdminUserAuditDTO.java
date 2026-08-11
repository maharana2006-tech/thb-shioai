package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserAuditDTO {
    private Long id;
    private Long subjectUserId;
    private String subjectUsername;
    private String action;
    private String oldClientCode;
    private String newClientCode;
    private String actorUsername;
    private String reason;
    private LocalDateTime createdAt;
}
