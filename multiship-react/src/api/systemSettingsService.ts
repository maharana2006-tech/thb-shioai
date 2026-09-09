import { apiClient } from './apiClient'

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

export const systemSettingsService = {
  list: () => apiClient.get<SystemSetting[]>('/admin/system-settings'),

  update: (key: string, value: string) =>
    apiClient.put<SystemSetting>(
      `/admin/system-settings/${encodeURIComponent(key)}`,
      { value },
    ),
}
