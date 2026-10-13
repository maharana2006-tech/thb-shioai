import { apiClient } from './apiClient'

/** The signed-in user's effective access (base role + visible modules). */
export interface Access {
  role: string
  baseRole: string | null
  modules: string[]
}

export const accessService = {
  get: (): Promise<Access> => apiClient.get<Access>('/me/access'),
}
