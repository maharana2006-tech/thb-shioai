package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** V115 — platform role registry (Auth Gap-5-A). */
@Entity
@Table(name = "role")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleEntity {

    @Id
    @Column(length = 32, nullable = false)
    private String code;

    @Column(length = 80)
    private String name;

    @Column(name = "is_invitable", nullable = false)
    @Builder.Default
    private Boolean isInvitable = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
