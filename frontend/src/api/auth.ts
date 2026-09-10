
import request from '@/utils/request'

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  nickname?: string
  email?: string
}

export const loginApi = (data: LoginRequest) => {
  return request.post('/api/v1/auth/login', data)
}

export const registerApi = (data: RegisterRequest) => request.post('/api/v1/auth/register', data)

export const logoutApi = () => {
  return request.post('/api/v1/auth/logout')
}

export const getCurrentUserApi = () => {
  return request.get('/api/v1/auth/me')
}
export const updateCurrentUserApi = (data: Record<string, unknown>) => request.put('/api/v1/auth/me', data)
