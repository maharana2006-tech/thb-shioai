import { apiClient } from './apiClient'

export interface LoginRequest {
  username: string
  password: string
}

export interface AuthResponse {
  token: string
  type: string; 
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