import request from '@/utils/request'

export const getAnalysisJobsApi = (params?: { status?: string; jobType?: string; scenarioCode?: string; batchId?: number; pageNum?: number; pageSize?: number }) =>
  request.get('/api/v1/analysis/jobs', { params })
export const getAnalysisJobApi = (jobId: string) =>
  request.get(`/api/v1/analysis/jobs/${jobId}`)
export const updateStructuredCaseAnalysisTextApi = (jobId: string, caseId: string, analysisText: string) =>
  request.put(`/api/v1/analysis/jobs/${jobId}/structured-results/${encodeURIComponent(caseId)}/analysis-text`, { analysisText })
export const createAnalysisJobApi = (data: Record<string, unknown>) =>
  request.post('/api/v1/analysis/jobs', data)
export const uploadStructuredCaseFilesApi = (files: {
  basicInfo: File
  customers: File
  recognitionMode: 'NEW' | 'HISTORICAL'
  analysisTexts?: File | null
  accounts?: File | null
  otherEntities?: File | null
}) => {
  const form = new FormData()
  form.append('basicInfo', files.basicInfo)
  form.append('customers', files.customers)
  form.append('recognitionMode', files.recognitionMode)
  if (files.analysisTexts) form.append('analysisTexts', files.analysisTexts)
  if (files.accounts) form.append('accounts', files.accounts)
  if (files.otherEntities) form.append('otherEntities', files.otherEntities)
  return request.post('/api/v1/analysis/structured-case-files', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}
export const uploadAntiFraudCaseFilesApi = (files: {
  basicInfo: File
  customers: File
  accounts: File
  devices: File
  recognitionMode: 'NEW' | 'HISTORICAL'
  eventChain?: File | null
  textAnalysis?: File | null
}) => {
  const form = new FormData()
  form.append('basicInfo', files.basicInfo)
  form.append('customers', files.customers)
  form.append('accounts', files.accounts)
  form.append('devices', files.devices)
  form.append('recognitionMode', files.recognitionMode)
  if (files.eventChain) form.append('eventChain', files.eventChain)
  if (files.textAnalysis) form.append('textAnalysis', files.textAnalysis)
  return request.post('/api/v1/analysis/anti-fraud-case-files', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}
export const uploadStructuredCaseBatchFileApi = (file: File, recognitionMode: 'NEW' | 'HISTORICAL') => {
  const form = new FormData()
  form.append('file', file)
  form.append('recognitionMode', recognitionMode)
  return request.post('/api/v1/analysis/structured-case-batch-file', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}
export const uploadAntiFraudCaseBatchFileApi = (file: File, recognitionMode: 'NEW' | 'HISTORICAL') => {
  const form = new FormData()
  form.append('file', file)
  form.append('recognitionMode', recognitionMode)
  return request.post('/api/v1/analysis/anti-fraud-case-batch-file', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000
  })
}
export const startAnalysisJobApi = (jobId: string) =>
  request.put(`/api/v1/analysis/jobs/${jobId}/start`)
export const updateAnalysisStepApi = (jobId: string, stepOrder: number, data: Record<string, unknown>) =>
  request.put(`/api/v1/analysis/jobs/${jobId}/steps/${stepOrder}`, data)
export const retryAnalysisJobApi = (jobId: string) =>
  request.put(`/api/v1/analysis/jobs/${jobId}/retry`)
export const cancelAnalysisJobApi = (jobId: string) =>
  request.put(`/api/v1/analysis/jobs/${jobId}/cancel`)
export const deleteAnalysisJobApi = (jobId: string) =>
  request.delete(`/api/v1/analysis/jobs/${jobId}`)
export const getWorkerConfigApi = (jobType = 'UNSTRUCTURED') =>
  request.get('/api/v1/analysis/worker-config', { params: { jobType } })
export const updateWorkerConfigApi = (content: string, jobType = 'UNSTRUCTURED') =>
  request.put('/api/v1/analysis/worker-config', { content }, { params: { jobType } })
export const getWorkerFilesApi = (jobType = 'UNSTRUCTURED') =>
  request.get('/api/v1/analysis/worker-files', { params: { jobType } })
export const getWorkerFileApi = (path: string, jobType = 'UNSTRUCTURED') =>
  request.get('/api/v1/analysis/worker-file', { params: { path, jobType } })
export const updateWorkerFileApi = (path: string, content: string, jobType = 'UNSTRUCTURED') =>
  request.put('/api/v1/analysis/worker-file', { path, content }, { params: { jobType } })
export const uploadWorkerZipApi = (file: File, jobType = 'UNSTRUCTURED') => {
  const form = new FormData()
  form.append('file', file)
  return request.post('/api/v1/analysis/worker-upload', form, {
    params: { jobType },
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
