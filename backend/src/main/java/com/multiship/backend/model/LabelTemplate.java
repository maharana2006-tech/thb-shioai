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

    /**
     * Discriminator for the document this template renders:
     * <ul>
     *   <li>{@code PACKING_SLIP} — the branded packing slip inserted inside
     *       the parcel. Rendered locally via {@code PackingSlipServiceImpl}.</li>
     *   <li>{@code COMMERCIAL_INVOICE} — customs CI for international
     *       shipments. Stored per-tenant so the client can override the
     *       platform default (fallback logic in
     *       {@code LabelTemplateService.resolve}). Rendering pipeline is
     *       carrier-side today; a local renderer is a future addition.</li>
     *   <li>{@code RETURN_COVER} — planned; return-label cover page.</li>
     * </ul>
     */
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

    /**
     * Phase 1 drag-drop editor payload — a JSON tree of layout blocks
     * (text / logo / address / items table / barcode / divider / spacer /
     * totals / signature) with per-block config + data bindings. Null =
     * template uses the legacy fields above (logo / color / header / footer
     * / showItems). Non-null = the layout tree drives rendering once the
     * Phase 2 renderer picks it up.
     *
     * <p>Stored as raw text so the schema doesn't fight Jackson on the
     * versioned block shape; frontend + backend agree on the tree structure
     * via the {@code TemplateLayout} TypeScript type.
     */
    @Column(name = "layout_json", columnDefinition = "text")
    private String layoutJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
