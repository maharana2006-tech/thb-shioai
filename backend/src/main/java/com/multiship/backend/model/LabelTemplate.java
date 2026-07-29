package com.multiship.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Sprint 42 — tenant-branded label template. Currently used for the
 * packing slip PDF (branded page that ships INSIDE the parcel). The
 * carrier's shipping label is NOT customisable — every carrier
 * mandates an exact barcode / address format on its label PDF; this
 * template only affects the branded insert we generate ourselves.
 *
 * <p>Per-tenant scoped via {@link #tenantId} (matches
 * {@code Client.customerNo}). Null tenant = platform-wide default
 * used when a tenant hasn't configured their own template.
 */
@Entity
@Table(name = "label_templates",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"tenant_id", "template_type"},
                name = "uk_label_template_tenant_type"))
@Data
public class LabelTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Customer number of the owning tenant; null = platform default. */
    @Column(name = "tenant_id", length = 50)
    private String tenantId;

    /** PACKING_SLIP for now; RETURN_COVER + COMMERCIAL_INVOICE future. */
    @Column(name = "template_type", nullable = false, length = 40)
    private String templateType = "PACKING_SLIP";

    /**
     * Base64-encoded PNG or JPEG logo. Stored inline so the renderer
     * doesn't need to hit a file store on the hot path. Cap ~200 KB
     * because base64 in a text column is slow past that.
     */
    @Column(name = "logo_base64", columnDefinition = "text")
    private String logoBase64;

    /**
     * Hex colour code for headings and accents (e.g. {@code #1f150c}).
     * The renderer parses it into RGB; null falls back to a neutral
     * dark tone.
     */
    @Column(name = "primary_color", length = 10)
    private String primaryColor;

    /** Header line printed at the top of the slip. Optional. */
    @Column(name = "header_text", length = 200)
    private String headerText;

    /**
     * Multi-line footer text (thank-you note, return instructions, etc.).
     * Rendered at the bottom. Up to ~500 characters recommended so the
     * layout doesn't reflow.
     */
    @Column(name = "footer_text", columnDefinition = "text")
    private String footerText;

    /** True to include the order-lines table on the slip. */
    @Column(name = "show_items")
    private Boolean showItems = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
