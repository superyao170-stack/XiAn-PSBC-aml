# kg-render — 框架表示 JSON → 知识图谱

把结构化案例识别产出的「框架表示」JSON 渲染成**单个可离线打开的 HTML**：圆形节点、按类型着色、可拖拽、带力导向动效。

不依赖 npm 安装、不依赖 CDN、不需要起服务——生成的 HTML 双击即可打开，也可以直接发给别人。

## 用法

```bash
node tools/graph-viewer/kg-render.mjs <输入.json|目录> [-o 输出.html] [--title 标题] [--open]
```

示例：

```bash
node tools/graph-viewer/kg-render.mjs workers/structured-case-identification/library/cases/CASE202504001_9d736cc1cedf.json --open
```

```bash
node tools/graph-viewer/kg-render.mjs workers/structured-case-identification/runs/JOB-3ec5d6e6-3c73-4b26-a316-f65375a95f91/extraction/0001_CASE202504001 -o artifacts/graphs/case.html
```

不指定 `-o` 时默认输出到 `artifacts/graphs/<输入文件名>.html`。

## 支持的输入

| 形态 | 说明 |
| --- | --- |
| 框架表示案例 JSON | `basic_info` / `customers` / `accounts` / `other_entities` / `events` / `evidences` / `relationships`，如 `final_case.json`、`library/cases/*.json` |
| 被包裹的框架表示 | `{ final_result: {...} }`、`{ result: {...} }`、`{ data: {...} }` 等，如 `08_merge.json` |
| 多案例 | JSON 数组或 `{ cases: [...] }`，合并渲染成一张图 |
| 通用图 | `{ nodes: [{id,label,type,...}], edges|links: [{source,target,label}] }` |
| 目录 | 优先读取目录内的 `final_case.json`，否则合并目录下所有 `*.json` |

节点主键为 `类型|ID`，因此事件 ID（`0000001`）与关系 ID 重号也不会串节点。

## 交互

- **拖拽节点**：拖动后该节点被固定（虚线圈标记），双击节点解除固定，「解除固定」按钮批量解除
- **拖拽空白**：平移画布；**滚轮**：以光标为中心缩放
- **悬停**：高亮该节点及其邻居，其余淡出，浮层显示前 7 个属性
- **单击**：右侧详情面板列出全部属性、全部关系、邻居节点（可点击跳转）
- **图例**：点击色块按类型显隐节点
- **搜索**：匹配名称 / ID / 任意属性值，回车定位并打开详情
- **布局**：力导向 / 辐射分层 / 类型环形
- **动效**：渐变颗粒节点、轻微呼吸、拖拽释放 Q 弹、高亮边流动虚线、聚焦光晕，可一键暂停
- **导出**：导出当前视图 PNG

## 文件

| 文件 | 作用 |
| --- | --- |
| `kg-render.mjs` | CLI：解析输入 → 装配图数据 → 内联生成 HTML |
| `kg-engine.js` | 渲染引擎：Canvas 2D 绘制 + 力导向模拟 + 交互，零依赖 |
| `shell.html` | HTML 外壳与样式，含 `/*__ENGINE__*/`、`/*__DATA__*/` 两个内联占位符 |

## 定制

- **节点配色 / 图标**：改 `kg-render.mjs` 顶部的 `TYPE_META`；未登记的类型自动从 `SPARE_COLORS` 取色
- **字段中文名**：改 `FIELD_LABELS`，未命中的字段按原 key 展示
- **力参数**：改 `kg-engine.js` 中的 `REPULSION` / `LINK_STRENGTH` / `GRAVITY` / 连线 `rest` 长度

## 在页面里复用引擎

`kg-engine.js` 挂载全局 `KG.render(root, payload, options)`，可脱离 CLI 直接使用：

```js
const app = KG.render(document.getElementById('app'), {
  meta:  { title: '标题', subtitle: '副标题', stats: [['节点', '20']] },
  types: [{ key: '客户', name: '客户', color: '#7c3aed', icon: '客' }],
  nodes: [{ id: '客户|CUST00001', rawId: 'CUST00001', type: '客户', label: '殷某敏', attrs: [['证件号码', '113***']] }],
  edges: [{ id: 'r1', source: '客户|CUST00001', target: '账户|ACCT00001', label: '持有关系' }]
})

app.select('客户|CUST00001')   // 定位并打开详情
app.fit()                      // 适配视图
app.destroy()                  // 停止动画循环
```

本项目的结构化案件结果页通过 `frontend/src/components/graph/KgRenderCanvas.vue`
加载同一份 `kg-engine.js` 与 `shell.html` 样式；案件处理结果中的 `graphSnapshot`
会在“图谱快照”标签页自动转换为引擎 payload 并内嵌显示。
