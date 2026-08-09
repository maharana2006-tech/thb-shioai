import { apiClient } from './apiClient'

/**
 * Sprint 49 Tier 0 — admin-managed encrypted secrets (OpenAI key, etc.).
 *
 * <p>List endpoint returns each known setting with a masked preview
 * ({@code "****" + last4}) and never the decrypted value. Update
 * accepts a plaintext {@code value} and the backend encrypts it before
 * persisting.
 */
export interface SystemSetting {
  key: string
  hasValue: boolean
  maskedValue: string
  description: string
}

export const systemSettingsService = {
  list: () => apiClient.get<SystemSetting[]>('/admin/system-settings'),

  update: (key: string, value: string) =>
    apiClient.put<SystemSetting>(
      `/admin/system-settings/${encodeURIComponent(key)}`,
      { value },
    ),
}
