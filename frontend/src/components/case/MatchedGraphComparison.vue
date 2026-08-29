<template>
  <section class="matched-graph-comparison">
    <div v-if="hasGraph" class="graphs">
      <div v-for="graph in graphs" :key="graph.side" class="graph-panel">
        <div class="graph-title">
          <h4>{{ graph.side === 'query' ? '新增案例' : '相似案例' }}</h4>
          <span>{{ graph.side === 'query' ? queryTitle : candidateTitle }}</span>
        </div>
        <svg :viewBox="`0 0 ${GRAPH_WIDTH} ${graph.height}`" :aria-label="`${graph.side}命中子图`" role="img">
          <defs>
            <pattern :id="`mesh-${graph.side}-${instanceId}`" width="18" height="18" patternUnits="userSpaceOnUse">
              <path d="M18 0H0V18" fill="none" stroke="#d9e2ef" stroke-width=".7" opacity=".45" />
            </pattern>
          </defs>
          <rect :width="GRAPH_WIDTH" :height="graph.height" :fill="`url(#mesh-${graph.side}-${instanceId})`" />
          <g v-for="lane in graph.lanes" :key="lane.kind">
            <rect class="lane" :class="`lane-${lane.kind}`" x="14" :y="lane.y" width="592" :height="lane.height" rx="16" />
            <text class="lane-label" x="30" :y="lane.y+24">{{ kindText(lane.kind) }}</text>
          </g>
          <path v-for="edge in graph.edges" :key="edge.key" class="graph-edge" :class="edge.state" :d="edge.path" />
          <g
            v-for="node in graph.nodes"
            :key="node.id"
            class="graph-node"
            :class="[`kind-${node.kind}`,node.matched?'matched':'unmatched',isSelected(node)?'is-selected':'']"
            :transform="`translate(${node.x},${node.y})`"
            role="button"
            tabindex="0"
            :aria-label="`查看节点 ${node.label||node.id} 的属性`"
            @click="selectNode(node)"
            @keydown.enter.prevent="selectNode(node)"
            @keydown.space.prevent="selectNode(node)"
          >
            <rect class="node-body" x="-72" y="-28" width="144" height="56" rx="16" />
            <circle class="node-icon" cx="-50" cy="0" r="15" />
            <text class="node-icon-text" x="-50" y="5" text-anchor="middle">{{ kindIcon(node.kind) }}</text>
            <text class="node-label" x="-28" y="5">{{ compactLabel(node.label||node.id) }}</text>
            <g v-if="node.matched" class="match-badge" transform="translate(59,-20)">
              <circle r="13" /><text text-anchor="middle" y="4">{{ node.matchIndex }}</text>
            </g>
          </g>
          <g v-for="edge in graph.edges" :key="`${edge.key}-label`" class="edge-chip" :class="edge.state" :transform="`translate(${edge.labelX},${edge.labelY})`">
            <rect x="-38" y="-11" width="76" height="22" rx="11" />
            <text text-anchor="middle" y="4">{{ edge.edge.relation }}</text>
          </g>
          <text v-if="!graph.nodes.length" x="310" y="105" text-anchor="middle" class="empty">案例图中没有节点</text>
        </svg>
      </div>
    </div>
    <el-empty v-else description="当前匹配结果未返回可视化子图" :image-size="56" />

    <div v-if="selectedNode" class="node-inspector">
      <div class="inspector-head">
        <div><small>NODE DETAILS</small><h4>{{ selectedNode.label || selectedNode.id }}</h4></div>
        <el-button circle size="small" aria-label="关闭节点详情" @click="selectedNode=null">×</el-button>
      </div>
      <div class="inspector-content">
        <section class="pair-panel">
          <el-tag size="small">{{ kindText(selectedNode.kind) }}</el-tag>
          <b>{{ selectedNode.label || selectedNode.id }}</b><code>{{ selectedNode.id }}</code>
          <template v-if="selectedNode.counterpart">
            <div class="pair-arrow">匹配到 →</div>
            <el-tag size="small" type="success">{{ kindText(selectedNode.counterpart.kind) }}</el-tag>
            <b>{{ selectedNode.counterpart.label || selectedNode.counterpart.id }}</b><code>{{ selectedNode.counterpart.id }}</code>
          </template>
          <ul v-if="selectedNode.connections.length">
            <li v-for="connection in selectedNode.connections" :key="`${connection.nodeId}-${connection.relation}`">
              {{ connection.relation }} → {{ connection.nodeId }}（{{ stateText(connection.state) }}）
            </li>
          </ul>
        </section>
        <section class="attribute-panel">
          <h5>节点数据与属性</h5>
          <dl>
            <template v-for="([key,value]) in attributeEntries(selectedNode)" :key="key">
              <dt>{{ attributeText(key) }}</dt><dd>{{ readable(value) }}</dd>
            </template>
          </dl>
        </section>
      </div>
    </div>

    <div class="counts">
      <span>命中节点 <strong>{{ matchedNodeCount }}</strong></span>
      <span>命中边 <strong>{{ matchedEdgeCount }}</strong></span>
      <span class="click-tip">点击节点查看数据与属性</span>
    </div>
    <details v-if="matchedEdges.length" class="edge-details">
      <summary>查看命中边明细</summary>
      <div class="table-scroll">
        <table><thead><tr><th>新增案例边</th><th>相似案例边</th><th>状态</th></tr></thead>
          <tbody><tr v-for="(item,index) in matchedEdges" :key="index">
            <td>{{ edgeText(item.query_edge||item.queryEdge) }}</td>
            <td>{{ edgeText(item.candidate_edge||item.candidateEdge) }}</td>
            <td><el-tag size="small" :type="edgeState(item)==='preserved'?'success':'warning'">{{ stateText(edgeState(item)) }}</el-tag></td>
          </tr></tbody>
        </table>
      </div>
    </details>
    <p v-else class="no-edges">本组没有同时命中的边</p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

const props=defineProps<{match:any,queryTitle?:string}>()
const GRAPH_WIDTH=620
const instanceId=Math.random().toString(36).slice(2,9)
const selectedNode=ref<any>(null)
const matched=computed(()=>props.match?.matchedSubgraph||props.match?.matched_subgraph||{})
const matchedNodes=computed<any[]>(()=>matched.value.matched_nodes||matched.value.matchedNodes||[])
const matchedEdges=computed<any[]>(()=>matched.value.matched_edges||matched.value.matchedEdges||[])
const matchedNodeCount=computed(()=>matched.value.matched_node_count??matched.value.matchedNodeCount??matchedNodes.value.length)
const matchedEdgeCount=computed(()=>matched.value.matched_edge_count??matched.value.matchedEdgeCount??matchedEdges.value.length)
const candidateTitle=computed(()=>props.match?.caseName||props.match?.case_name||props.match?.caseId||props.match?.case_id||'相似案例')

type Side='query'|'candidate'
const value=(object:any,snake:string,camel:string)=>object?.[snake]??object?.[camel]
const edgeSignature=(edge:any)=>[edge?.left,edge?.right,edge?.relation,edge?.description||''].join('\u0001')
const edgeState=(item:any)=>item?.match_type||item?.matchType||'substituted'
const kindText=(kind:string)=>({person:'人员',entity:'实体 / 账户',event:'事件'} as any)[kind]||kind||'节点'
const kindIcon=(kind:string)=>({person:'人',entity:'实',event:'事'} as any)[kind]||'点'
const compactLabel=(text:any)=>{const result=String(text||'').replace(/\s+/g,' ');return result.length<=11?result:`${result.slice(0,10)}…`}
const stateText=(state:string)=>({preserved:'保持',substituted:'替换',unmatched:'未命中'} as any)[state]||state

function buildGraph(side:Side){
  const prefix=side,other=side==='query'?'candidate':'query'
  const full=value(matched.value,`${prefix}_full_graph`,`${prefix}FullGraph`)||{}
  const mappingById=new Map(matchedNodes.value.map(item=>[value(item,`${prefix}_id`,`${prefix}Id`),item]))
  let sourceNodes:any[]=full.nodes||[]
  if(!sourceNodes.length) sourceNodes=matchedNodes.value.map(item=>({
    id:value(item,`${prefix}_id`,`${prefix}Id`),kind:value(item,`${prefix}_kind`,`${prefix}Kind`),
    subtype:value(item,`${prefix}_subtype`,`${prefix}Subtype`),label:value(item,`${prefix}_label`,`${prefix}Label`),
    description:value(item,`${prefix}_description`,`${prefix}Description`),attributes:value(item,`${prefix}_attributes`,`${prefix}Attributes`)||{}
  }))
  const nodes=sourceNodes.map(node=>{
    const item=mappingById.get(node.id) as any
    return {...node,side,matched:!!item,matchIndex:item?.match_index??item?.matchIndex,
      counterpart:item?{id:value(item,`${other}_id`,`${other}Id`),kind:value(item,`${other}_kind`,`${other}Kind`),label:value(item,`${other}_label`,`${other}Label`),subtype:value(item,`${other}_subtype`,`${other}Subtype`),description:value(item,`${other}_description`,`${other}Description`)}:null,
      connections:[] as any[]}
  })
  const kinds=['person','entity','event'],lanes:any[]=[],positions=new Map<string,{x:number,y:number,laneTop:number}>()
  let currentY=20
  for(const kind of kinds){
    const items=nodes.filter(node=>node.kind===kind).sort((a,b)=>(Number(!a.matched)-Number(!b.matched))||((a.matchIndex||0)-(b.matchIndex||0))||String(a.id).localeCompare(String(b.id)))
    if(!items.length)continue
    const rows=Math.ceil(items.length/3),laneHeight=112+rows*112
    lanes.push({kind,y:currentY,height:laneHeight})
    for(let row=0;row<rows;row++){
      const rowItems=items.slice(row*3,row*3+3),gap=(GRAPH_WIDTH-52)/(rowItems.length+1)
      rowItems.forEach((item,index)=>positions.set(item.id,{x:26+gap*(index+1),y:currentY+152+row*112,laneTop:currentY}))
    }
    currentY+=laneHeight+12
  }
  const matchedEdgeMap=new Map(matchedEdges.value.map(item=>{
    const edge=value(item,`${prefix}_edge`,`${prefix}Edge`);return[edgeSignature(edge),edgeState(item)]
  }))
  const sourceEdges:any[]=full.edges?.length?full.edges:matchedEdges.value.map(item=>value(item,`${prefix}_edge`,`${prefix}Edge`)).filter(Boolean)
  const edges=sourceEdges.flatMap((edge,index)=>{
    const left=positions.get(edge.left),right=positions.get(edge.right);if(!left||!right)return[]
    const sameLane=Math.abs(left.y-right.y)<5,midX=(left.x+right.x)/2,midY=(left.y+right.y)/2
    const controlY=sameLane?Math.min(left.laneTop,right.laneTop)+38+(index%2)*18:midY
    const labelY=sameLane?Math.min(left.laneTop,right.laneTop)+72+(index%2)*18:midY
    const state=matchedEdgeMap.get(edgeSignature(edge))||'unmatched'
    return[{key:`${edge.left}-${edge.relation}-${edge.right}-${index}`,edge,state,path:`M${left.x},${left.y} Q${midX},${controlY} ${right.x},${right.y}`,labelX:midX,labelY}]
  })
  const byId=new Map(nodes.map(node=>[node.id,node]))
  edges.forEach(item=>{byId.get(item.edge.left)?.connections.push({nodeId:item.edge.right,relation:item.edge.relation,state:item.state});byId.get(item.edge.right)?.connections.push({nodeId:item.edge.left,relation:item.edge.relation,state:item.state})})
  nodes.forEach(node=>Object.assign(node,positions.get(node.id)||{x:0,y:0}))
  return{side,nodes,edges,lanes,height:Math.max(210,currentY+8)}
}

const graphs=computed(()=>[buildGraph('query'),buildGraph('candidate')])
const hasGraph=computed(()=>graphs.value.some(graph=>graph.nodes.length))
const isSelected=(node:any)=>selectedNode.value?.side===node.side&&selectedNode.value?.id===node.id
const selectNode=(node:any)=>{selectedNode.value=node}
const readable=(value:any)=>Array.isArray(value)?value.join('、'):value&&typeof value==='object'?JSON.stringify(value):String(value??'—')
const attributeEntries=(node:any)=>Object.entries({节点类型:kindText(node.kind),子类型:node.subtype||'—',节点描述:node.description||'—',...(node.attributes||{})})
const attributeText=(key:string)=>({event_id:'事件ID',event_name:'事件名称',event_type:'事件类型',event_description:'事件描述',entity_id:'实体ID',customer_name:'客户名称',risk_type:'风险类型',risk_indicator:'风险指标',recognition_rule:'识别规则',event_start_date:'开始日期',event_end_date:'结束日期',product_service:'产品/服务',value_tool:'价值工具',disposition_measures:'处置措施'} as any)[key]||key
const edgeText=(edge:any)=>edge?`${edge.left} — ${edge.relation} — ${edge.right}`:'—'
</script>

<style scoped>
.matched-graph-comparison{margin-top:14px;padding:16px;border:1px solid #d9e3f0;border-radius:14px;background:rgba(255,255,255,.82)}
.graphs{display:grid;grid-template-columns:1fr 1fr;gap:18px}.graph-panel{overflow:hidden;border:1px solid #d5dfec;border-radius:18px;background:#f8fbff;box-shadow:inset 0 1px #fff}.graph-title{display:flex;align-items:baseline;gap:10px;padding:14px 18px;border-bottom:1px solid #dce5f0;background:linear-gradient(90deg,#edf4fc,#f8fbff)}.graph-title h4{margin:0;color:#174b82;font-size:14px}.graph-title span{overflow:hidden;color:#64748b;font-size:12px;text-overflow:ellipsis;white-space:nowrap}svg{display:block;width:100%;height:auto}
.lane{fill:#fff;stroke:#dfe7f1;stroke-width:1}.lane-person{fill:#f4f8ff}.lane-entity{fill:#f8f5ff}.lane-event{fill:#fffbf2}.lane-label{fill:#8492a6;font-size:11px;font-weight:700;letter-spacing:.08em}.graph-edge{fill:none;stroke:#aab6c5;stroke-width:3;stroke-linecap:round;opacity:.76}.graph-edge.preserved{stroke:#28a45f}.graph-edge.substituted{stroke:#f07a45}.graph-edge.unmatched{stroke:#c1cad6;stroke-dasharray:6 5}.edge-chip{pointer-events:none}.edge-chip rect{fill:#fff;stroke:#b8c4d2;stroke-width:1.5}.edge-chip.preserved rect{stroke:#28a45f}.edge-chip.substituted rect{stroke:#f07a45}.edge-chip.unmatched rect{stroke:#b8c4d2}.edge-chip text{fill:#344054;font-size:10px}
.graph-node{cursor:pointer;outline:none}.node-body{stroke-width:1.5;transition:filter .18s,stroke-width .18s}.kind-person .node-body{fill:#eef5ff;stroke:#8bb9f8}.kind-entity .node-body{fill:#f5efff;stroke:#b69aef}.kind-event .node-body{fill:#fff5dc;stroke:#efbd5e}.graph-node.unmatched{opacity:.42}.node-icon{fill:#fff}.kind-person .node-icon{stroke:#2563eb;stroke-width:2}.kind-entity .node-icon{stroke:#7c3aed;stroke-width:2}.kind-event .node-icon{stroke:#d97706;stroke-width:2}.node-icon-text{fill:#344054;font-size:11px;font-weight:700}.node-label{fill:#172033;font-size:12px;font-weight:650}.match-badge circle{fill:#173f69}.match-badge text{fill:#fff;font-size:10px;font-weight:700}.graph-node:hover .node-body,.graph-node:focus .node-body{filter:drop-shadow(0 6px 8px #25466b2b);stroke-width:2.5}.graph-node.is-selected .node-body{stroke:#0d3157;stroke-width:3;filter:drop-shadow(0 7px 10px #153c6638)}.empty{fill:#98a2b3}
.node-inspector{margin-top:18px;padding:18px 20px;border:1px solid #cbd9e8;border-left:5px solid #1f5d9d;border-radius:16px;background:linear-gradient(120deg,#f8fbff,#fff)}.inspector-head{display:flex;align-items:flex-start;justify-content:space-between}.inspector-head small{color:#7c8da1;letter-spacing:.12em}.inspector-head h4{margin:4px 0 0;font-size:18px}.inspector-content{display:grid;grid-template-columns:minmax(230px,.8fr) 1.4fr;gap:20px;margin-top:14px}.pair-panel,.attribute-panel{padding:15px;border:1px solid #e0e7ef;border-radius:13px;background:#fff}.pair-panel{display:flex;align-items:flex-start;flex-direction:column;gap:5px}.pair-panel code{color:#64748b;word-break:break-all}.pair-panel ul{margin:8px 0 0;padding-left:18px;color:#475569;font-size:12px}.pair-arrow{margin:5px 0;color:#7b8794}.attribute-panel h5{margin:0 0 10px}.attribute-panel dl{display:grid;grid-template-columns:minmax(110px,.35fr) 1fr;margin:0}.attribute-panel dt,.attribute-panel dd{margin:0;padding:8px 6px;border-bottom:1px solid #eef2f6;font-size:13px;word-break:break-word}.attribute-panel dt{color:#64748b}
.counts{display:flex;align-items:center;gap:18px;margin:16px 2px 5px;color:#64748b;font-size:13px}.click-tip{margin-left:auto;color:#245b92}.edge-details{margin-top:10px;padding-top:10px;border-top:1px solid #edf1f5}.edge-details summary{cursor:pointer;color:#315f8c;font-size:13px}.table-scroll{overflow-x:auto}.edge-details table{width:100%;margin-top:8px;border-collapse:collapse}.edge-details th,.edge-details td{padding:10px;border-bottom:1px solid #edf1f5;text-align:left}.edge-details td{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}.no-edges{margin:12px 2px 0;color:#98a2b3;font-size:12px}
@media(max-width:1100px){.graphs,.inspector-content{grid-template-columns:1fr}}@media(max-width:560px){.counts{flex-wrap:wrap}.click-tip{width:100%;margin-left:0}}
</style>
