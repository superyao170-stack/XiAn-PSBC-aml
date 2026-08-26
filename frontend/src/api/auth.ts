
import request from '@/utils/request'

export interface LoginRequest {
  username: string
  password: string
}

export const loginApi = (data: LoginRequest) => {
  return request.post('/api/v1/auth/login', data)
}

export const logoutApi = () => {
  return request.post('/api/v1/auth/logout')
}

export const getCurrentUserApi = () => {
  return request.get('/api/v1/auth/me')
}
export const updateCurrentUserApi = (data: Record<string, unknown>) => request.put('/api/v1/auth/me', data)
