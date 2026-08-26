import request from '@/utils/request'

export const getOverviewApi = (params?: { startDate?: string; endDate?: string; scenarioCode?: string }) =>
  request.get('/api/v1/overview', { params })
