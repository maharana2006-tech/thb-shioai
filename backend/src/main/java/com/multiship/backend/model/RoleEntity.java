package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

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

    /** For a custom role: the built-in role (ADMIN/USER/TENANT) it inherits
     *  backend permissions from. NULL for the built-in roles themselves. */
    @Column(name = "base_role", length = 32)
    private String baseRole;

    /** Module keys this role may see in the UI (custom roles only). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_module", joinColumns = @JoinColumn(name = "role_code"))
    @Column(name = "module_key", length = 64)
    @Builder.Default
    private Set<String> modules = new HashSet<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
