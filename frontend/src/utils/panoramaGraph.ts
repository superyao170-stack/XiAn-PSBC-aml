export const graphNodeId = (node: any): string => String(node.id ?? node.uid ?? '')
export const graphNodeType = (node: any): string => String(node.visualType || node.label || node.properties?.nodeType || node.type || 'OTHER').replace(/[^A-Za-z0-9]/g, '').toUpperCase()
export const graphCaseKey = (node: any): string => String(node.visualCaseId || node.properties?.caseId || node.caseId || '__unassigned__')
export const graphNodeName = (node: any): string => String(node.visualLabel || node.name || node.properties?.displayName || node.properties?.caseName || node.properties?.eventName || node.properties?.name || node.properties?.accountNo || graphNodeId(node))
const relationNames: Record<string, string> = {
  RELATES_TO: '关联', HAS_EVENT: '包含事件', INVOLVES: '涉及', INVOLVES_CUSTOMER: '涉及客户', INVOLVES_ACCOUNT: '涉及账户',
  HAS_ACCOUNT: '持有账户', OWNS: '持有', TRANSFER: '转账', TRANSFER_TO: '转账至',
  SIMILAR_CASE: '相似案件', HAS_EVIDENCE: '关联证据', USES_DEVICE: '使用设备',
  OCCURRED_AT: '发生于', PARTICIPATES_IN: '参与', RELATED_TO: '关联', BELONGS_TO: '所属'
}
export const graphEdgeName = (edge: any): string => {
  const value = String(edge.properties?.relationName || edge.properties?.relationType || edge.visualLabel || edge.label || edge.type || edge.relationType || '关联')
  return relationNames[value] || value
}
export const graphEdgeKey = (edge: any): string => `${edge.id || edge.properties?.graphId || ''}|${edge.source}|${edge.target}|${graphEdgeName(edge)}`
export function graphRelationMeta(edge: any) {
  const text = `${edge.type || ''} ${graphEdgeName(edge)}`
  if (edge.properties?.edgeCategory === 'INFERENCE') return { key: 'inference', label: '事理推理关系', color: '#b87924', dash: '5 4', directed: true }
  if (/TRANSFER|PAY|TRANSACTION|转账|资金|汇款|收款|支付/i.test(text)) return { key: 'flow', label: '资金流向', color: '#5c82b0', dash: '', directed: true }
  if (/DEVICE|LOGIN|IP_ADDRESS|登录|设备|IP地址/i.test(text)) return { key: 'device', label: '设备关联', color: '#53988b', dash: '2 5', directed: true }
  if (/SIMILAR|相似|共同/i.test(text)) return { key: 'shared', label: '相似 / 共同关联', color: '#8d9fb6', dash: '6 5', directed: !/SIMILAR|相似/i.test(text) }
  return { key: 'relation', label: '实体关系', color: '#9aabbe', dash: '', directed: true }
}
export const shortGraphText = (text: string, limit = 12) => Array.from(text).length > limit ? `${Array.from(text).slice(0, limit).join('')}…` : text
export interface PanoramaPoint { id: string; raw: any; x: number; y: number; caseKey: string; collapsed: boolean; count: number }
export interface PanoramaGroup { id: string; title: string; x: number; y: number; width: number; height: number; count: number; collapsed: boolean }
export interface PanoramaLayout { nodes: PanoramaPoint[]; groups: PanoramaGroup[]; width: number; height: number }

// Allocate a full label footprint to every node, rather than fitting the graph
// into the viewport by shrinking node spacing. The viewport is fitted afterwards.
export function layoutPanorama(nodes: any[], edges: any[], expanded: Set<string>, clusterMode = true): PanoramaLayout {
  const buckets = new Map<string, any[]>()
  nodes.forEach(node => { const key = graphCaseKey(node); if (!buckets.has(key)) buckets.set(key, []); buckets.get(key)!.push(node) })
  const adjacency = new Map<string, Set<string>>()
  edges.forEach(edge => {
    const a = String(edge.source), b = String(edge.target)
    if (!adjacency.has(a)) adjacency.set(a, new Set())
    if (!adjacency.has(b)) adjacency.set(b, new Set())
    adjacency.get(a)!.add(b); adjacency.get(b)!.add(a)
  })
  const groups: Array<{ group: PanoramaGroup; points: PanoramaPoint[] }> = []
  buckets.forEach((items, key) => {
    const root = items.find(node => node.visualPrimary) || items.find(node => graphNodeType(node) === 'CASE') || items[0]
    const collapsed = clusterMode && nodes.length > 120 && !expanded.has(key) && graphNodeType(root) === 'CASE'
    const title = String(root.visualCaseName || root.properties?.caseName || graphNodeName(root))
    if (collapsed) {
      groups.push({ group: { id: key, title, x: 0, y: 0, width: 184, height: 112, count: items.length, collapsed }, points: [{ id: graphNodeId(root), raw: root, x: 92, y: 53, caseKey: key, collapsed, count: items.length }] })
      return
    }
    const ids = new Set(items.map(graphNodeId)), depth = new Map<string, number>([[graphNodeId(root), 0]])
    const queue = [graphNodeId(root)]
    for (let index = 0; index < queue.length; index++) {
      const current = queue[index]
      for (const next of adjacency.get(current) || []) {
        if (!ids.has(next) || depth.has(next)) continue
        depth.set(next, Math.min(3, depth.get(current)! + 1)); queue.push(next)
      }
    }
    const levels: any[][] = [[], [], [], []]
    items.forEach(node => levels[depth.get(graphNodeId(node)) ?? 3].push(node))
    levels.forEach(level => level.sort((a, b) => (adjacency.get(graphNodeId(b))?.size || 0) - (adjacency.get(graphNodeId(a))?.size || 0) || graphNodeName(a).localeCompare(graphNodeName(b))))
    const rowLimit = Array.from({ length: 8 }, (_, i) => i + 3).sort((a, b) => {
      const score = (limit: number) => {
        const columns = levels.reduce((sum, level) => sum + Math.ceil(level.length / limit), 0)
        const rows = Math.max(1, ...levels.map(level => Math.min(limit, level.length)))
        return Math.abs(Math.log(((columns * 168 + 42) / (rows * 96 + 108)) / 1.85))
      }
      return score(a) - score(b)
    })[0]
    const maxRows = Math.max(1, ...levels.map(level => Math.min(rowLimit, level.length)))
    let column = 0
    const points: PanoramaPoint[] = []
    levels.forEach(level => {
      if (!level.length) return
      const columns = Math.ceil(level.length / rowLimit)
      level.forEach((node, i) => {
        const rowCount = Math.min(rowLimit, level.length - Math.floor(i / rowLimit) * rowLimit)
        points.push({ id: graphNodeId(node), raw: node, caseKey: key, collapsed: false, count: 0,
          x: 104 + (column + Math.floor(i / rowLimit)) * 168,
          y: 92 + (i % rowLimit) * 96 + (maxRows - rowCount) * 48 })
      })
      column += columns
    })
    groups.push({ group: { id: key, title, x: 0, y: 0, width: column * 168 + 42, height: maxRows * 96 + 108, count: items.length, collapsed: false }, points })
  })
  const area = groups.reduce((sum, { group }) => sum + (group.width + 32) * (group.height + 32), 0)
  const boardWidth = Math.max(1000, Math.sqrt(area * 1.65), ...groups.map(({ group }) => group.width))
  let x = 24, y = 24, shelfHeight = 0, right = 0
  const points: PanoramaPoint[] = []
  groups.forEach(({ group, points: local }) => {
    if (x > 24 && x + group.width > boardWidth) { x = 24; y += shelfHeight + 30; shelfHeight = 0 }
    group.x = x; group.y = y
    local.forEach(point => points.push({ ...point, x: point.x + x, y: point.y + y }))
    right = Math.max(right, x + group.width); shelfHeight = Math.max(shelfHeight, group.height); x += group.width + 30
  })
  return { nodes: points, groups: groups.map(item => item.group), width: right + 24, height: y + shelfHeight + 24 }
}

export function graphNeighborhood(id: string, nodes: any[], edges: any[]) {
  const byId = new Map(nodes.map(node => [graphNodeId(node), node]))
  const neighbors = new Map<string, Array<{ id: string; edge: any }>>()
  edges.forEach(edge => {
    const source = String(edge.source), target = String(edge.target)
    if (!byId.has(source) || !byId.has(target)) return
    if (!neighbors.has(source)) neighbors.set(source, [])
    if (!neighbors.has(target)) neighbors.set(target, [])
    neighbors.get(source)!.push({ id: target, edge }); neighbors.get(target)!.push({ id: source, edge })
  })
  const nodeIds = new Set([id]), edgeKeys = new Set<string>(), path: string[] = []
  for (const item of neighbors.get(id) || []) { nodeIds.add(item.id); edgeKeys.add(graphEdgeKey(item.edge)) }
  if (graphNodeType(byId.get(id) || {}) === 'CASE') return { nodeIds, edgeKeys, path: [id] }
  const queue = [id], parents = new Map<string, { id: string; edge: any }>(), seen = new Set([id])
  let target = ''
  for (let i = 0; i < queue.length && !target; i++) {
    for (const next of neighbors.get(queue[i]) || []) {
      if (seen.has(next.id)) continue
      seen.add(next.id); parents.set(next.id, { id: queue[i], edge: next.edge })
      if (graphNodeType(byId.get(next.id)) === 'CASE') { target = next.id; break }
      queue.push(next.id)
    }
  }
  while (target) {
    path.push(target); nodeIds.add(target)
    const parent = parents.get(target)
    if (!parent) break
    edgeKeys.add(graphEdgeKey(parent.edge)); target = parent.id
  }
  return { nodeIds, edgeKeys, path }
}
