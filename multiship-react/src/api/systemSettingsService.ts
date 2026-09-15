import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

/**
 * Sprint 49 Tier 0 — admin-managed encrypted secrets (OpenAI key, etc.).
 *
 * <p>List endpoint returns each known setting with a masked preview
 * ({@code "****" + last4}) and never the decrypted value. Update
 * accepts a plaintext {@code value} and the backend encrypts it before
 * persisting.
 */
/**
 * Render hint from the backend registry: SECRET → password input +
 * masked preview; CHOICE → radio picker over {@link options} with
 * {@link currentValue} highlighted.
 */
export type SystemSettingKind = 'SECRET' | 'CHOICE'

export interface SystemSetting {
  key: string
  hasValue: boolean
  maskedValue: string
  description: string
  kind?: SystemSettingKind | null
  options?: string[] | null
  currentValue?: string | null
  defaultValue?: string | null
}

/**
 * USPS provider state machine used by the USPS_DIRECT integration
 * (PR-A). Three values:
 *  - {@code STAMPS_COM} — legacy Stamps.com SWSIM/SERA connector
 *    (default). Only Stamps.com credentials rendered on the drawer;
 *    USPS Direct fields hidden.
 *  - {@code PROVISIONING_USPS_DIRECT} — parallel-run state. Both the
 *    Stamps.com credentials AND the USPS Direct fields are visible so
 *    operators can pre-populate CRID / MID / EPS account number ahead
 *    of the platform switch. Runtime label calls still hit Stamps.com.
 *  - {@code USPS_DIRECT} — cutover complete. Only USPS Direct fields
 *    rendered; Stamps.com values remain in the DB for rollback but are
 *    not shown.
 */
export type UspsProviderMode =
  | 'STAMPS_COM'
  | 'PROVISIONING_USPS_DIRECT'
  | 'USPS_DIRECT'

/**
 * Readiness snapshot returned by
 * {@code GET /admin/system-settings/USPS_PROVIDER/readiness}. Gates the
 * transition from PROVISIONING → USPS_DIRECT: the operator can save
 * {@code USPS_DIRECT} only when {@link #overallReady} is true, i.e.
 * platform credentials are set AND every USPS carrier account has been
 * provisioned with EPS account # + CRID + MID.
 *
 * <p>The server-side gate is authoritative — the FE uses this DTO to
 * disable the save button + surface a helpful tooltip, but a save that
 * bypasses the gate is rejected with HTTP 409 carrying the same DTO in
 * the error body.
 */
export interface UspsProviderReadiness {
  /** Value currently persisted for {@code USPS_PROVIDER}. */
  currentProvider: UspsProviderMode
  /** Value the FE is asking about (usually {@code USPS_DIRECT}). */
  targetProvider: UspsProviderMode
  /** Platform-level OAuth credentials for the USPS Direct API. */
  platformCreds: {
    clientIdSet: boolean
    clientSecretSet: boolean
  }
  /** Total USPS carrier accounts (platform + client, active + inactive). */
  totalUspsAccounts: number
  /** USPS carrier accounts with all three USPS Direct fields populated. */
  readyAccounts: number
  /** Per-tenant breakdown of accounts still missing USPS Direct fields. */
  pendingAccounts: Array<{
    tenantCode: string
    accountNumber: string
    missing: Array<
      | 'usps_direct_account_number'
      | 'usps_direct_crid'
      | 'usps_direct_mid'
    >
  }>
  /**
   * True when {@link #platformCreds} are both set AND
   * {@link #readyAccounts} equals {@link #totalUspsAccounts}. When
   * false, saving {@code USPS_PROVIDER=USPS_DIRECT} is blocked.
   */
  overallReady: boolean
}

export const systemSettingsService = {
  list: () => apiClient.get<SystemSetting[]>('/admin/system-settings'),

  update: (key: string, value: string) =>
    apiClient.put<SystemSetting>(
      `/admin/system-settings/${encodeURIComponent(key)}`,
      { value },
    ),

  /**
   * USPS_DIRECT provider readiness snapshot. See {@link UspsProviderReadiness}
   * for shape + semantics. Called from:
   *  - {@link SystemSettingsPage} when the operator picks
   *    {@code USPS_DIRECT} on the {@code USPS_PROVIDER} choice
   *    (pre-save gate).
   *  - {@link UspsProviderReadinessTable} while the setting is in the
   *    {@code PROVISIONING_USPS_DIRECT} state, so the ops team sees the
   *    per-tenant checklist inline on {@code /settings/system}.
   */
  getUspsProviderReadiness: () =>
    apiClient.get<ApiResponse<UspsProviderReadiness>>(
      '/admin/system-settings/USPS_PROVIDER/readiness',
    ),
}
