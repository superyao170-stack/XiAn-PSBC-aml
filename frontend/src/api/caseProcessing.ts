import request from '@/utils/request'

export const getCaseProcessingApi = (params: Record<string, unknown>) =>
  request.get('/api/v1/case-processing', { params })
export const getCaseProcessingDetailApi = (caseId: string) =>
  request.get(`/api/v1/case-processing/${encodeURIComponent(caseId)}`)
export const getApprovedCaseSimilarityGraphApi = () =>
  request.get('/api/v1/case-processing/similarity-graph/approved')
export const processCasesApi = (action: 'REPORT'|'FRAMEWORK'|'SIMILARITY', caseIds: string[]) =>
  request.post(`/api/v1/case-processing/actions/${action}`, { caseIds }, { timeout: 3600000 })
export const updateProcessingReportApi = (caseId: string, analysisText: string) =>
  request.put(`/api/v1/case-processing/${encodeURIComponent(caseId)}/report`, { analysisText })
export const approveProcessingCaseApi = (caseId: string, riskLevel: 'LOW'|'MEDIUM'|'HIGH', orderedSimilarCaseIds: string[]) =>
  request.put(`/api/v1/case-processing/${encodeURIComponent(caseId)}/approve`, { riskLevel, orderedSimilarCaseIds })
