#!/usr/bin/env node
/**
 * kg-render — 把「框架表示」JSON 渲染成单文件、可离线打开的知识图谱 HTML。
 *
 *   node tools/graph-viewer/kg-render.mjs <输入.json> [-o 输出.html] [--open]
 *
 * 支持的输入格式：
 *   1. 框架表示案例 JSON（basic_info / customers / accounts / other_entities / events / relationships）
 *      —— 例如 workers/structured-case-identification/**\/final_case.json、library/cases/*.json
 *   2. 上述结构被 { final_result: {...} } / { result: {...} } / { data: {...} } 包裹的形式
 *   3. 通用图 JSON：{ nodes:[{id,label,type,...}], edges|links:[{source,target,label}] }
 *   4. 由多个案例组成的数组，或 { cases:[...] } —— 合并渲染为一张图
 *
 * 输出为不依赖网络、不依赖 npm 的单个 HTML 文件（引擎与数据全部内联）。
 */
import { readFileSync, writeFileSync, existsSync, statSync, readdirSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join, basename } from 'node:path'
import { execFile } from 'node:child_process'

const HERE = dirname(fileURLToPath(import.meta.url))

/* ------------------------------------------------------------------ */
/* 节点类型与配色                                                       */
/* ------------------------------------------------------------------ */

const TYPE_META = {
  案例: { name: '案例', color: '#2563eb', icon: '案' },
  客户: { name: '客户', color: '#7c3aed', icon: '客' },
  账户: { name: '账户', color: '#0891b2', icon: '户' },
  其他实体: { name: '其他实体', color: '#64748b', icon: '其' },
  事件: { name: '事件', color: '#ea580c', icon: '事' },
  证据: { name: '证据', color: '#059669', icon: '证' }
}
const SPARE_COLORS = ['#059669', '#dc2626', '#a16207', '#4f46e5', '#be123c', '#0f766e', '#9333ea', '#0369a1']

/* 字段中文名。未命中的字段按原样展示。 */
const FIELD_LABELS = {
  case_id: '案例编号', case_name: '案例名称', case_description: '案情描述', business_domain: '业务领域',
  case_type: '案例类型', submission_direction: '报送方向', case_trigger: '触发方式', urgency: '紧急程度',
  report_date: '报送日期', case_status: '案例状态', risk_level: '风险等级', suspected_crime_type: '涉嫌罪名',
  suspicious_transaction_codes: '可疑代码', disposition_measures: '处置措施',
  entity_id: '实体编号', customer_name: '客户名称', customer_number: '客户号', id_type: '证件类型',
  id_number: '证件号码', occupation_industry: '职业/行业', nationality: '国籍', address: '地址',
  customer_risk_level: '客户风险等级', legal_representative_name: '法定代表人',
  legal_representative_id_type: '法人证件类型', legal_representative_id_number: '法人证件号',
  controller_name: '实际控制人', controller_id_type: '控制人证件类型', controller_id_number: '控制人证件号',
  account_type: '账户类型', holder_name: '开户人', holder_id_type: '开户人证件类型',
  holder_id_number: '开户人证件号', account_open_date: '开户日期', account_close_date: '销户日期',
  account_number: '账号', bank_card_type: '卡类型', bank_card_number: '卡号',
  event_id: '事件编号', event_name: '事件名称', event_type: '事件类型', event_description: '事件描述',
  event_start_date: '开始日期', event_end_date: '结束日期', recognition_rule: '识别规则',
  product_service: '产品服务', value_tool: '价值工具', risk_type: '风险类型', risk_indicator: '风险指标',
  entity_attr_1: '实体名称', entity_attr_2: '实体类别', entity_attr_3: '关联行为', entity_attr_4: '发生日期',
  evidence_id: '证据编号', evidence_name: '证据名称', evidence_type: '证据类型',
  relationship_id: '关系编号', relationship_type: '关系类型', relationship_description: '关系描述'
}

const labelOf = (key) => FIELD_LABELS[key] || key

const toText = (value) => {
  if (value == null) return ''
  if (Array.isArray(value)) return value.map(toText).filter(Boolean).join('、')
  if (typeof value === 'object') return JSON.stringify(value, null, 0)
  return String(value)
}

const attrsOf = (record, skip = []) => Object.entries(record || {})
  .filter(([key, value]) => !skip.includes(key) && toText(value) !== '')
  .map(([key, value]) => [labelOf(key), toText(value)])

/* ------------------------------------------------------------------ */
/* 输入解析                                                            */
/* ------------------------------------------------------------------ */

function unwrap (raw) {
  let value = raw
  for (let depth = 0; depth < 4; depth += 1) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) break
    if (value.relationships || value.nodes || value.edges || value.links) break
    const next = value.final_result || value.result || value.data || value.case || value.final_case
    if (!next) break
    value = next
  }
  return value
}

function collectCases (raw) {
  const value = unwrap(raw)
  if (Array.isArray(value)) return value.map(unwrap)
  if (value && Array.isArray(value.cases)) return value.cases.map(unwrap)
  return [value]
}

const isFrameworkCase = (value) => Boolean(value && typeof value === 'object' &&
  (Array.isArray(value.relationships) || Array.isArray(value.customers) || Array.isArray(value.events)))

/* ------------------------------------------------------------------ */
/* 框架表示 → 图数据                                                    */
/* ------------------------------------------------------------------ */

function buildGraph (cases) {
  const nodes = new Map()   // key = `${type}|${id}`
  const edges = []
  const stats = { case: 0, relationship: 0 }

  const keyOf = (type, id) => `${type}|${id}`

  const upsert = (type, id, label, attrs, primary) => {
    if (id == null || id === '') return null
    const key = keyOf(type, id)
    const existing = nodes.get(key)
    if (existing) {
      if (attrs && attrs.length > existing.attrs.length) existing.attrs = attrs
      if (label && existing.label === existing.id) existing.label = label
      if (primary) existing.primary = true
      return existing
    }
    const node = { id: key, rawId: String(id), type, label: String(label || id), attrs: attrs || [], primary: Boolean(primary) }
    nodes.set(key, node)
    return node
  }

  cases.forEach((item) => {
    if (!isFrameworkCase(item)) return
    stats.case += 1
    const info = item.basic_info || {}
    const caseId = info.case_id || item.case_id || `CASE-${stats.case}`
    upsert('案例', caseId, info.case_name || caseId, attrsOf(info, ['case_name']), true)

    ;(item.customers || []).forEach((row) => {
      upsert('客户', row.entity_id, row.customer_name || row.entity_id, attrsOf(row, ['customer_name']))
    })
    ;(item.accounts || []).forEach((row) => {
      const label = row.account_number ? `${row.account_number}` : (row.holder_name || row.entity_id)
      upsert('账户', row.entity_id, label, attrsOf(row))
    })
    ;(item.other_entities || []).forEach((row) => {
      upsert('其他实体', row.entity_id, row.entity_attr_1 || row.entity_id, attrsOf(row))
    })
    ;(item.events || []).forEach((row) => {
      upsert('事件', row.event_id, row.event_name || row.event_id, attrsOf(row, ['event_name']))
    })
    ;(item.evidences || []).forEach((row, index) => {
      const id = row.evidence_id || row.entity_id || `EVID${index + 1}`
      upsert('证据', id, row.evidence_name || row.name || id, attrsOf(row))
    })

    ;(item.relationships || []).forEach((row, index) => {
      const sourceType = row.source_node_type || '其他实体'
      const targetType = row.target_node_type || '其他实体'
      const source = upsert(sourceType, row.source_node_id, row.source_node_name, [])
      const target = upsert(targetType, row.target_node_id, row.target_node_name, [])
      if (!source || !target) return
      stats.relationship += 1
      edges.push({
        id: `${caseId}-${row.relationship_id || index}`,
        source: source.id,
        target: target.id,
        label: row.relationship_type || '关联',
        desc: row.relationship_description || ''
      })
    })
  })

  return { nodes: Array.from(nodes.values()), edges, stats }
}

/* 通用 {nodes, edges} 输入 */
function buildGenericGraph (raw) {
  const source = unwrap(raw)
  const rawNodes = source.nodes || []
  const rawEdges = source.edges || source.links || source.relationships || []
  const nodes = rawNodes.map((row, index) => {
    const id = String(row.id ?? row.key ?? row.name ?? index)
    const type = String(row.type || row.category || row.label_type || row.nodeType || '节点')
    const label = String(row.label ?? row.name ?? row.title ?? id)
    const attrs = attrsOf(row.attrs || row.properties || row, ['id', 'key', 'label', 'name', 'type', 'category', 'attrs', 'properties'])
    return { id, rawId: id, type, label, attrs, primary: Boolean(row.primary) }
  })
  const known = new Set(nodes.map((node) => node.id))
  const edges = rawEdges.map((row, index) => ({
    id: String(row.id ?? index),
    source: String(row.source ?? row.from ?? row.source_node_id ?? ''),
    target: String(row.target ?? row.to ?? row.target_node_id ?? ''),
    label: String(row.label ?? row.type ?? row.relationship_type ?? ''),
    desc: String(row.desc ?? row.description ?? row.relationship_description ?? '')
  })).filter((edge) => known.has(edge.source) && known.has(edge.target))
  return { nodes, edges, stats: { case: 0, relationship: edges.length } }
}

/* ------------------------------------------------------------------ */
/* 渲染                                                                */
/* ------------------------------------------------------------------ */

function buildPayload (graph, meta) {
  const seen = []
  graph.nodes.forEach((node) => { if (!seen.includes(node.type)) seen.push(node.type) })
  let spare = 0
  const types = seen.map((key) => {
    const preset = TYPE_META[key]
    if (preset) return { key, ...preset }
    const color = SPARE_COLORS[spare % SPARE_COLORS.length]
    spare += 1
    return { key, name: key, color, icon: String(key).slice(0, 1) }
  })
  return {
    meta,
    types,
    nodes: graph.nodes.map(({ id, rawId, type, label, attrs, primary }) => ({ id, rawId, type, label, attrs, primary })),
    edges: graph.edges
  }
}

function emitHtml (payload, outputPath) {
  const engine = readFileSync(join(HERE, 'kg-engine.js'), 'utf8')
  const shell = readFileSync(join(HERE, 'shell.html'), 'utf8')
  const json = JSON.stringify(payload)
    .replace(/</g, '\\u003c')
    .replace(new RegExp('\u2028', 'g'), '\\u2028')
    .replace(new RegExp('\u2029', 'g'), '\\u2029')
  const html = shell
    .replace('__TITLE__', String(payload.meta.title || '知识图谱').replace(/[<>]/g, ''))
    .replace('/*__ENGINE__*/', () => engine)
    .replace('/*__DATA__*/null', () => json)
  writeFileSync(outputPath, html, 'utf8')
}

/* ------------------------------------------------------------------ */
/* CLI                                                                 */
/* ------------------------------------------------------------------ */

function parseArgs (argv) {
  const args = { input: null, output: null, open: false, title: null }
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i]
    if (token === '-o' || token === '--output') { args.output = argv[++i]; continue }
    if (token === '--title') { args.title = argv[++i]; continue }
    if (token === '--open') { args.open = true; continue }
    if (token === '-h' || token === '--help') { args.help = true; continue }
    if (!args.input) args.input = token
  }
  return args
}

function resolveInput (target) {
  const path = resolve(target)
  if (!existsSync(path)) throw new Error(`输入不存在：${path}`)
  if (!statSync(path).isDirectory()) return [path]
  // 目录：优先 final_case.json，其次目录内全部 *.json
  const direct = join(path, 'final_case.json')
  if (existsSync(direct)) return [direct]
  const files = readdirSync(path).filter((name) => name.endsWith('.json')).map((name) => join(path, name))
  if (!files.length) throw new Error(`目录内没有 JSON 文件：${path}`)
  return files
}

function main () {
  const args = parseArgs(process.argv.slice(2))
  if (args.help || !args.input) {
    process.stdout.write(`用法：
  node tools/graph-viewer/kg-render.mjs <输入.json|目录> [-o 输出.html] [--title 标题] [--open]

示例：
  node tools/graph-viewer/kg-render.mjs workers/structured-case-identification/library/cases/CASE202504001_9d736cc1cedf.json --open
  node tools/graph-viewer/kg-render.mjs workers/structured-case-identification/runs/JOB-xxx/extraction/0001_CASE202504001 -o artifacts/graph.html
`)
    process.exit(args.help ? 0 : 1)
  }

  const files = resolveInput(args.input)
  const parsed = files.flatMap((file) => collectCases(JSON.parse(readFileSync(file, 'utf8'))))
  const frameworkCases = parsed.filter(isFrameworkCase)

  const graph = frameworkCases.length
    ? buildGraph(frameworkCases)
    : buildGenericGraph(JSON.parse(readFileSync(files[0], 'utf8')))

  if (!graph.nodes.length) throw new Error('未能从输入中解析出任何节点，请确认 JSON 结构')

  const first = frameworkCases[0] || {}
  const info = first.basic_info || {}
  const typeTally = new Map()
  graph.nodes.forEach((node) => typeTally.set(node.type, (typeTally.get(node.type) || 0) + 1))

  const title = args.title || (frameworkCases.length > 1
    ? `${frameworkCases.length} 个案例知识图谱`
    : (info.case_name || basename(files[0], '.json')))

  const subtitle = [info.case_id, info.risk_level, info.suspected_crime_type, info.report_date]
    .filter(Boolean).join(' · ') || `来源：${basename(files[0])}`

  const payload = buildPayload(graph, {
    title,
    subtitle,
    stats: [
      ['节点', String(graph.nodes.length)],
      ['关系', String(graph.edges.length)],
      ...Array.from(typeTally.entries()).slice(0, 5).map(([key, count]) => [key, String(count)])
    ]
  })

  const output = resolve(args.output || join(process.cwd(), 'artifacts', 'graphs', `${basename(files[0], '.json')}.html`))
  const dir = dirname(output)
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true })
  emitHtml(payload, output)

  process.stdout.write(`已生成知识图谱：${output}\n节点 ${graph.nodes.length} 个 · 关系 ${graph.edges.length} 条 · 类型 ${payload.types.length} 种\n`)
  if (args.open) execFile('open', [output], () => {})
}

main()
