package com.multiship.backend.dto;

import com.multiship.backend.model.AuditLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogDTO {
    private Long id;
    private String actor;
    private String action;
    private String entityType;
    private String entityId;
    private String entityKey;
    private String changes;
    private String notes;
    private LocalDateTime createdAt;

    public static AuditLogDTO from(AuditLog e) {
        return AuditLogDTO.builder()
                .id(e.getId())
                .actor(e.getActor())
                .action(e.getAction())
                .entityType(e.getEntityType())
                .entityId(e.getEntityId())
                .entityKey(e.getEntityKey())
                .changes(e.getChanges())
                .notes(e.getNotes())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
