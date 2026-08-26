import request from '@/utils/request'
import type { StructSchema, RiskIngestBatch } from '@/types'

export const getSchemasApi = (params?: { pageNum?: number; pageSize?: number; schemaName?: string; category?: string }) =>
  request.get('/api/v1/data/schema', { params })
export const createSchemaApi = (data: Partial<StructSchema>) => request.post('/api/v1/data/schema', data)
export const updateSchemaApi = (id: number, data: Partial<StructSchema>) =>
  request.put(`/api/v1/data/schema/${id}`, data)
export const deleteSchemaApi = (id: number) => request.delete(`/api/v1/data/schema/${id}`)
export const getSchemaDetailApi = (id: number) => request.get(`/api/v1/data/schema/${id}`)
export const updateSchemaStatusApi = (id: number, status: string) =>
  request.put(`/api/v1/data/schema/${id}/status`, { status })
export const createSchemaDraftApi = (id: number, changelog?: string) =>
  request.post(`/api/v1/data/schema/${id}/draft`, { changelog })
export const replaceSchemaFieldsApi = (id: number, fields: Array<Record<string, unknown>>) =>
  request.put(`/api/v1/data/schema/${id}/fields`, fields)
export const getSchemaFeedbackApi = (params?: { status?: string; pageNum?: number; pageSize?: number }) =>
  request.get('/api/v1/data/schema-feedback', { params })
export const reviewSchemaFeedbackApi = (id: number, decision: 'ACCEPTED'|'REJECTED') =>
  request.put(`/api/v1/data/schema-feedback/${id}/review`, { decision })

export const getBatchesApi = (params: {
  pageNum: number
  pageSize: number
  batchNo?: string
  status?: string
} = { pageNum: 1, pageSize: 10 }) =>
  request.get('/api/v1/data/batches', { params })
export const createBatchApi = (data: Partial<RiskIngestBatch>) => request.post('/api/v1/data/batches', data)
export const uploadBatchApi = (data: FormData) =>
  request.post('/api/v1/data/batches/upload', data, { headers: { 'Content-Type': 'multipart/form-data' } })
export const uploadStructuredBatchApi = (data: FormData, onProgress?: (percentage: number) => void) =>
  request.post('/api/v1/data/batches/upload/ibm-aml', data, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: event => {
      if (event.total && onProgress) onProgress(Math.round(event.loaded * 100 / event.total))
    }
  })
export const checkBatchNameConflictsApi = (data: FormData) =>
  request.post('/api/v1/data/batches/name-conflicts', data, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
export const getStructuredBatchOptionsApi = () =>
  request.get('/api/v1/data/batches/structured-options')
export const getBatchDetailApi = (id: number) => request.get(`/api/v1/data/batches/${id}`)
export const updateBatchStatusApi = (id: number, status: string, errorMessage?: string) =>
  request.put(`/api/v1/data/batches/${id}/status`, { status, errorMessage })
export const deleteBatchApi = (id: number) =>
  request.delete(`/api/v1/data/batches/${id}`)
export const downloadAssetApi = (id: number, assetType: string) =>
  request.get(`/api/v1/data/batches/${id}/download/${assetType}`)
export const createUnstructuredBatchApi = (params: { workspaceId: number; bankCode?: string; batchNo?: string; autoSchema?: boolean; schemaId?: number }) =>
  request.post('/api/v1/data/unstructured/batches', null, { params })
export const uploadUnstructuredFileApi = (batchId: number, file: File, canonicalBatchName?: string) => {
  const form = new FormData()
  form.append('file', file)
  if (canonicalBatchName) form.append('canonicalBatchName', canonicalBatchName)
  return request.post(`/api/v1/data/unstructured/batches/${batchId}/files`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
export const uploadUnstructuredBatchApi = (data: FormData) =>
  request.post('/api/v1/data/unstructured/batches/upload', data, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
export const canonicalizeUnstructuredBatchNameApi = (batchId: number, batchNo: string) =>
  request.put(`/api/v1/data/unstructured/batches/${batchId}/canonical-name`, null, { params: { batchNo } })
export const getUnstructuredBatchOptionsApi = () =>
  request.get('/api/v1/data/unstructured/batches/options')
export const getUnstructuredBatchesApi = (params: {
  pageNum: number
  pageSize: number
  batchNo?: string
  status?: string
} = { pageNum: 1, pageSize: 10 }) =>
  request.get('/api/v1/data/unstructured/batches', { params })
export const getUnstructuredBatchDetailApi = (batchId: number) =>
  request.get(`/api/v1/data/unstructured/batches/${batchId}`)

export const getQuarantineApi = (params?: { status?: string; bankCode?: string }) =>
  request.get('/api/v1/data/quarantine', { params })
export const fixQuarantineApi = (recordId: string, data: { operator: string; method: string }) =>
  request.put(`/api/v1/data/quarantine/${recordId}/fix`, data)
export const replayQuarantineApi = (recordId: string, operator: string) =>
  request.put(`/api/v1/data/quarantine/${recordId}/replay`, { operator })
export const getQualityRulesApi = () => request.get('/api/v1/data/quality/rules')
export const createQualityRuleApi = (data: Record<string, unknown>) =>
  request.post('/api/v1/data/quality/rules', data)
export const runBatchQualityApi = (batchId: number) =>
  request.post(`/api/v1/data/quality/batches/${batchId}/run`)
export const getBatchQualityReportApi = (batchId: number) =>
  request.get(`/api/v1/data/quality/batches/${batchId}/report`)
