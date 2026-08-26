import request from '@/utils/request'

const base = '/api/v1/research-analytics'

export const getTask23CaseContextApi = (caseId: string) =>
  request.get(`${base}/cases/${encodeURIComponent(caseId)}/context`)
export const createTask23CaseRunApi = (
  caseId: string,
  data: { runType: string; parameters?: Record<string, unknown> }
) => request.post(`${base}/cases/${encodeURIComponent(caseId)}/runs`, data)
export type Task23Scope = {
  bankCode?: string
  scenarioCode?: string
  startTime?: string
  endTime?: string
  caseIds?: string[]
  limit?: number
}
export const getTask23ScopeContextApi = (data: Task23Scope) =>
  request.post(`${base}/scopes/context`, data)
export const createTask23ScopeRunApi = (
  data: Task23Scope & { runType: string; parameters?: Record<string, unknown> }
) => request.post(`${base}/scopes/runs`, data)
export const getResearchRunsApi = (params?: Record<string, unknown>) =>
  request.get(`${base}/runs`, { params })
export const getResearchRunApi = (runId: string) =>
  request.get(`${base}/runs/${encodeURIComponent(runId)}`)
export const getResearchRunArtifactsApi = (runId: string) =>
  request.get(`${base}/runs/${encodeURIComponent(runId)}/artifacts`)
export const reviewResearchArtifactApi = (artifactId: string, status: 'APPROVED' | 'REJECTED') =>
  request.put(`${base}/artifacts/${encodeURIComponent(artifactId)}/review`, { status })
