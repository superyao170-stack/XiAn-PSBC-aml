export type AnalysisScopeState = {
  caseIds: string[]
  scenarioCode: string
  startTime: string
  endTime: string
}

const STORAGE_KEY = 'bankgraph.analysisScope'

const uniqueIds = (value: unknown): string[] => {
  const text = Array.isArray(value) ? value.join(',') : String(value || '')
  return [...new Set(text.split(',').map(item => item.trim()).filter(Boolean))]
}

export const readAnalysisScope = (query: Record<string, unknown> = {}): AnalysisScopeState => {
  let stored: Partial<AnalysisScopeState> = {}
  try {
    stored = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '{}')
  } catch {
    stored = {}
  }
  const queryIds = uniqueIds(query.caseIds)
  return {
    caseIds: queryIds.length ? queryIds : uniqueIds(stored.caseIds),
    scenarioCode: String(query.scenarioCode || stored.scenarioCode || ''),
    startTime: String(query.startTime || stored.startTime || ''),
    endTime: String(query.endTime || stored.endTime || ''),
  }
}

export const saveAnalysisScope = (scope: AnalysisScopeState) => {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify({
    caseIds: uniqueIds(scope.caseIds),
    scenarioCode: scope.scenarioCode || '',
    startTime: scope.startTime || '',
    endTime: scope.endTime || '',
  }))
}
