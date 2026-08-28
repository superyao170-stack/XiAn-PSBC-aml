import request from '@/utils/request'
import type { SysUser, SysRole, SysMenu } from '@/types'

export const getUsersApi = (params = { pageNum: 1, pageSize: 100 }) =>
  request.get('/api/v1/system/users', { params })
export const createUserApi = (data: Partial<SysUser>) => request.post('/api/v1/system/users', data)
export const updateUserApi = (id: number, data: Partial<SysUser>) => request.put(`/api/v1/system/users/${id}`, data)
export const deleteUserApi = (id: number) => request.delete(`/api/v1/system/users/${id}`)

export const getRolesApi = () => request.get('/api/v1/system/roles')
export const createRoleApi = (data: Partial<SysRole>) => request.post('/api/v1/system/roles', data)
export const updateRoleApi = (id: number, data: Partial<SysRole>) => request.put(`/api/v1/system/roles/${id}`, data)
export const deleteRoleApi = (id: number) => request.delete(`/api/v1/system/roles/${id}`)

export const getMenusApi = () => request.get('/api/v1/system/menus')
export const createMenuApi = (data: Partial<SysMenu>) => request.post('/api/v1/system/menus', data)
export const updateMenuApi = (id: number, data: Partial<SysMenu>) => request.put(`/api/v1/system/menus/${id}`, data)
export const deleteMenuApi = (id: number) => request.delete(`/api/v1/system/menus/${id}`)

export interface EventMetadataDefinition {
  id: string
  name: string
  category: string
  rule: string
}

export const getEventMetadataApi = (scenarioCode: 'AML' | 'ANTI_FRAUD') =>
  request.get('/api/v1/system/event-metadata', { params: { scenarioCode } })
export const createEventMetadataApi = (scenarioCode: 'AML' | 'ANTI_FRAUD', data: EventMetadataDefinition) =>
  request.post('/api/v1/system/event-metadata', data, { params: { scenarioCode } })
export const updateEventMetadataApi = (scenarioCode: 'AML' | 'ANTI_FRAUD', eventId: string, data: EventMetadataDefinition) =>
  request.put(`/api/v1/system/event-metadata/${encodeURIComponent(eventId)}`, data, { params: { scenarioCode } })
export const deleteEventMetadataApi = (scenarioCode: 'AML' | 'ANTI_FRAUD', eventId: string) =>
  request.delete(`/api/v1/system/event-metadata/${encodeURIComponent(eventId)}`, { params: { scenarioCode } })

export const getDictApi = (dictType: string) => request.get(`/api/v1/system/dict/${dictType}`)
export const createDictApi = (dictType: string, data: Record<string, unknown>) =>
  request.post(`/api/v1/system/dict/${dictType}`, data)
export const updateDictApi = (dictType: string, id: number, data: Record<string, unknown>) =>
  request.put(`/api/v1/system/dict/${dictType}/${id}`, data)
export const deleteDictApi = (dictType: string, id: number) =>
  request.delete(`/api/v1/system/dict/${dictType}/${id}`)
