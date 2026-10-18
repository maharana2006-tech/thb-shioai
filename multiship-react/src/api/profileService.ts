import { apiClient } from './apiClient'

/** The logged-in user's own account (self-service Profile page). */
export interface Profile {
  username: string
  email: string
  fullName: string | null
  role: string
  clientCode: string | null
  emailVerified: boolean
  /** Small client-resized profile photo as a data URL, or null for none (show initials). */
  avatar: string | null
  /** ISO timestamp the account was created, or null for legacy rows. */
  createdAt: string | null
  /** The operator's preferred carrier, or null when none is set. */
  preferredCarrier: string | null
}

export const profileService = {
  get: (): Promise<Profile> => apiClient.get<Profile>('/auth/profile'),

  /** Update the editable part of the profile (display name). */
  update: (fullName: string): Promise<Profile> =>
    apiClient.put<Profile>('/auth/profile', { fullName }),

  /** Set (data URL) or remove (null) the own profile photo. Returns the updated profile. */
  updateAvatar: (avatar: string | null): Promise<Profile> =>
    apiClient.put<Profile>('/auth/profile/avatar', { avatar }),

  /** Change own password; the backend verifies the old one and revokes existing sessions. */
  changePassword: (oldPassword: string, newPassword: string): Promise<unknown> =>
    apiClient.post('/auth/password/change', { oldPassword, newPassword }),

  /** Email a password-reset link to the given address (public, always 202). */
  sendResetEmail: (email: string): Promise<unknown> =>
    apiClient.post('/auth/password/forgot', { email }),

  /** Deactivate own account (last-admin guarded); revokes sessions on success. */
  deactivate: (): Promise<unknown> => apiClient.post('/auth/profile/deactivate', {}),
}
