package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "ship_vias")
@Data
public class ShipVia {

    @Id
    private Integer id;

    @Column(name = "shipvia_cd")
    private String shipviaCd;

    @Column(name = "shipvia_desc")
    private String shipviaDesc;

    @Column(name = "vendor_id")
    private String vendorId;

    @Column(name = "api_base_url")
    private String apiBaseUrl;

    @Column(name = "auth_url")
    private String authUrl;

    @Column(name = "api_version")
    private String apiVersion;

    @Column(name = "sandbox_url")
    private String sandboxUrl;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "is_active")
    private Boolean active;

    @Column(name = "documentation_url")
    private String documentationUrl;

    @Column(name = "connection_guide")
    private String connectionGuide;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
