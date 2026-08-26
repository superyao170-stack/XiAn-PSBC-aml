
export interface SysUser {
  id: number
  username: string
  password: string
  nickname: string
  email: string
  phone: string
  avatar: string
  status: boolean
  roleCode: string
  bankCode: string
  institutionId: number
  createdAt: string
  updatedAt: string
  deleted: boolean
}

export interface SysRole {
  id: number
  roleCode: string
  roleName: string
  description: string
  menus: string[]
  permissions: string[]
}

export interface SysMenu {
  id: number
  parentId: number
  menuName: string
  path: string
  component: string
  icon: string
  sortOrder: number
  type: string
  permission: string
  visible: boolean
  children?: SysMenu[]
}

export interface CfRiskCase {
  id: number
  caseId: string
  caseName: string
  description: string
  caseVersion: number
  bankCode: string
  institutionId: number
  orgId: number
  workspaceId: number
  scenarioCode: string
  scenarioVersion: string
  caseSource: string
  caseType: string
  caseStatus: string
  businessDomain: string
  businessCaseType: string
  reportingDirection: string
  triggerPoint: string
  urgencyLevel: string
  reportedAt: string
  businessCaseStatus: string
  businessRiskLevel: string
  suspectedCrimeType: string
  suspiciousTransactionFeatureCode: string
  disposalMeasure: string
  riskScore: number
  riskLevel: string
  subjectCount: number
  transactionCount: number
  totalAmount: string
  graphSnapshotId: string
  ownerAnalyst: string
  reviewer: string
  approver: string
  createdAt: string
  updatedAt: string
  eventCount: number
  signalCount: number
  patternName: string
  patternProductCount: number
  matterProductCount: number
  atomicMatterProductCount: number
  riskProductCount: number
  techniqueProductCount: number
  productSnapshotCount: number
  productionStatus: 'PRODUCED' | 'PENDING' | 'NOT_READY'
  recognitionMode: 'NEW' | 'HISTORICAL' | ''
  lastProducedAt: string
}

export interface CfRiskEvent {
  id: number
  eventId: string
  caseId: string
  caseVersion: number
  bankCode: string
  eventName: string
  eventType: string
  eventStandardCode: string
  eventTime: string
  confidence: number
  riskScore: number
  riskLevel: string
  ruleName: string
}

export interface RiskSignal {
  id: number
  signalId: string
  bankCode: string
  workspaceId: number
  scenarioCode: string
  signalType: string
  score: number
  decision: string
  status: string
  createdAt: string
}

export interface SharedSchema {
  id: number
  schemaId: string
  schemaName: string
  schemaVersion: string
  schemaDefinition: string
  category: string
  status: string
  publishedAt: string
}

export interface SharedClue {
  id: number
  clueId: string
  clueType: string
  description: string
  evidenceJson: string
  confidence: number
  sourceBankCode: string
  status: string
}

export interface PendingActivation {
  id: number
  activationId: string
  bankCode: string
  contentType: string
  contentId: string
  contentVersion: string
  status: string
  createdAt: string
}

export interface StructSchema {
  id: number
  schemaCode: string
  schemaName: string
  description: string
  category: string
  versionCount: number
  latestVersion: string
  bankCode: string
  status: string
}

export interface RiskIngestBatch {
  id: number
  batchNo: string
  bankCode: string
  workspaceId: number
  schemaVersion: string
  sourceCount: number
  acceptedCount: number
  rejectedCount: number
  duplicateCount: number
  status: string
  createdAt: string
  publishedAt: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  refreshToken: string
  username: string
  nickname: string
  roleCode: string
  bankCode: string
  permissions: string[]
  menus: SysMenu[]
}
