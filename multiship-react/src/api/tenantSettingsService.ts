/**
 * Client for the per-tenant preferences endpoint (slice-1 backend
 * shipped as PR #725). First consumer: {@code enabledChannels} —
 * D2C / B2B / both — which gates the external API + WMS pull (slice
 * 2 PR #726). Rendered on {@code /settings/system} via
 * {@code SystemChannelSection}.
 */
import { apiClient } from './apiClient'
import type { ApiResponse } from './orderService'

export type ShippingChannel = 'D2C' | 'B2B'

export interface EnabledChannelsResponse {
  tenantCode: string
  /** Alphabetically-sorted list of enabled channels (backend
   *  normalises on write; empty when the tenant hasn't picked yet). */
  enabledChannels: ShippingChannel[]
  /** {@code false} until the tenant has explicitly chosen at least
   *  one channel. Force-picking default — the intake gate rejects
   *  orders while this is false. */
  isConfigured: boolean
}

export interface EnabledChannelsRequest {
  enabledChannels: ShippingChannel[]
}

export const tenantSettingsService = {
  /**
   * Fetch the tenant's enabled channels. Returns the same shape as
   * {@code PUT} — {@code isConfigured=false + enabledChannels=[]}
   * when the row doesn't exist yet.
   */
  getEnabledChannels: (tenantCode: string) =>
    apiClient
      .get<ApiResponse<EnabledChannelsResponse>>(
        `/tenants/${encodeURIComponent(tenantCode)}/settings/enabled-channels`,
      )
      .then((res) => res.data),

  /**
   * Set the tenant's enabled channels. Backend requires at least one
   * value (D2C, B2B, or both) — an empty list surfaces as 400 with a
   * "pick at least one shipping channel" message.
   */
  setEnabledChannels: (tenantCode: string, channels: ShippingChannel[]) =>
    apiClient
      .put<ApiResponse<EnabledChannelsResponse>>(
        `/tenants/${encodeURIComponent(tenantCode)}/settings/enabled-channels`,
        { enabledChannels: channels } as EnabledChannelsRequest,
      )
      .then((res) => res.data),
}
