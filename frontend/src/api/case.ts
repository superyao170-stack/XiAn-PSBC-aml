import request from '@/utils/request'
import type { CfRiskCase } from '@/types'

export const getCasesApi = (params?: Record<string, unknown>) => request.get('/api/v1/cases', { params })
export const getSuspiciousTransactionsApi = (params?: Record<string, unknown>) =>
  request.get('/api/v1/suspicious-transactions', { params })
export const getSuspiciousTransactionApi = (signalId: string) =>
  request.get(`/api/v1/suspicious-transactions/${signalId}`)
export const updateSuspiciousTransactionApi = (signalId: string, data: Record<string, unknown>) =>
  request.put(`/api/v1/suspicious-transactions/${signalId}`, data)
export const reviewSuspiciousTransactionsApi = (data: Record<string, unknown>) =>
  request.post('/api/v1/suspicious-transactions/review', data)
export const caseSuspiciousTransactionsApi = (data: Record<string, unknown>) =>
  request.post('/api/v1/suspicious-transactions/case', data)
export const getCaseApi = (caseId: string) => request.get(`/api/v1/cases/${caseId}`)
export const updateCaseApi = (caseId: string, data: Partial<CfRiskCase>) => request.put(`/api/v1/cases/${caseId}`, data)
export const updateCaseOverviewApi = (caseId: string, data: Record<string, unknown>) =>
  request.put(`/api/v1/cases/${caseId}/overview`, data)
export const deleteCaseApi = (caseId: string) => request.delete(`/api/v1/cases/${caseId}`)
export const submitCaseApi = (caseId: string) => request.put(`/api/v1/cases/${caseId}/submit`)
export const reviewCaseApi = (caseId: string, data: Record<string, unknown>) =>
  request.put(`/api/v1/cases/${caseId}/review`, data)
export const approveCaseApi = (caseId: string, data: Record<string, unknown>) =>
  request.put(`/api/v1/cases/${caseId}/approve`, data)
export const closeCaseApi = (caseId: string) => request.put(`/api/v1/cases/${caseId}/close`)
export const getCaseEventsApi = (caseId: string) => request.get(`/api/v1/cases/${caseId}/events`)
export const getCaseSignalsApi = (caseId: string) => request.get(`/api/v1/cases/${caseId}/signals`)
export const getCaseWorkflowApi = (caseId: string) => request.get(`/api/v1/cases/${caseId}/workflow`)
export const getCaseReplaysApi = (caseId: string) => request.get(`/api/v1/cases/${caseId}/replays`)
export const getCaseGraphApi = (caseId: string) => request.get('/api/v1/graph/case-subgraph', { params: { caseId } })
export const createCaseFallbackApi = (caseId: string) => request.post('/api/v1/graph/case-fallback', { caseId })
export const getCaseWorkerResultApi = (caseId: string) => request.get(`/api/v1/cases/${caseId}/worker-result`)
export const createCaseReplayApi = (caseId: string, data: Record<string, unknown>) =>
  request.post(`/api/v1/cases/${caseId}/replays`, data)
export const refreshCaseMattersApi = (caseId: string) =>
  request.post(`/api/v1/cases/${caseId}/matter-explanations/refresh`)
export const getCaseMattersApi = (caseId: string) =>
  request.get(`/api/v1/cases/${caseId}/matter-explanations`)
export const getCaseMatterEvidenceApi = (caseId: string, matterId: string) =>
  request.get(`/api/v1/cases/${caseId}/matter-explanations/${matterId}/evidence`)
export const getCaseReasoningApi = (caseId: string) =>
  request.get(`/api/v1/cases/${caseId}/reasoning`)
export const getCaseCoreChainApi = (caseId: string) =>
  request.get(`/api/v1/cases/${caseId}/core-chains/current`)
export const getCaseKnowledgeExplanationChainsApi = (
  caseId: string,
  params?: { view?: 'FULL'|'PANORAMA'; limit?: number }
) => request.get(`/api/v1/cases/${caseId}/knowledge-explanation-chains`, { params })
export const refreshCaseCoreChainApi = (caseId: string) =>
  request.post(`/api/v1/cases/${caseId}/core-chains/refresh`)
export const updateInvestigationHypothesisApi = (
  caseId: string,
  hypothesisId: string,
  data: Record<string, unknown>
) => request.put(`/api/v1/cases/${caseId}/investigation-hypotheses/${hypothesisId}`, data)
export const feedbackCaseMatterApi = (caseId: string, matterId: string, data: Record<string, unknown>) =>
  request.put(`/api/v1/cases/${caseId}/matter-explanations/${matterId}/feedback`, data)
export const getCaseTechniquesApi = (caseId: string) =>
  request.get(`/api/v1/cases/${caseId}/technique-occurrences`)
export const getCaseReviewSuggestionsApi = (caseId: string) =>
  request.get(`/api/v1/cases/${caseId}/review-suggestions`)
export const updateCaseReviewSuggestionApi = (caseId: string, suggestionId: string, data: Record<string, unknown>) =>
  request.put(`/api/v1/cases/${caseId}/review-suggestions/${suggestionId}`, data)
