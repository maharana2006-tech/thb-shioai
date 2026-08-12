import { apiClient } from './apiClient'

export interface LoginRequest {
  username: string
  password: string
}

// Sprint 50 PR Q3 — cookie-mode auth: JWT lives in an httpOnly cookie
// set by the backend, no longer in the response body. `token` is
// declared optional because the backend field was retained (null-valued)
// so old FE code doesn't crash on the missing key; new code must not
// depend on it.
export interface AuthResponse {
  token?: string | null
  type?: string
  username: string
  role: string
}

export interface SignupRequest {
  username: string
  email: string
  password: string
  fullName: string
  role?: string
}

export interface MessageResponse {
  message: string
}

export const authService = {
  
  login: async (data: LoginRequest) => {
    return apiClient.post<AuthResponse>('/auth/login', data)
  },
  logout: async () => {
    return apiClient.post<MessageResponse>('/auth/logout', {})
  },
  signup: async (data: SignupRequest) => {
    return apiClient.post<MessageResponse>('/auth/signup', data)
  },
}