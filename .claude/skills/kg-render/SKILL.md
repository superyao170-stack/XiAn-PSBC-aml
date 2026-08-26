---
name: kg-render
description: 把「框架表示」案例 JSON（basic_info/customers/accounts/other_entities/events/relationships）或通用 {nodes,edges} JSON 渲染成可交互知识图谱 HTML——圆形节点、按类型着色、可拖拽、力导向动效。当用户要求"渲染图谱""把这个 JSON 画成知识图谱""看看这个案例的图""可视化实体关系"，或提到 final_case.json / library/cases/*.json 时使用。
---

# 知识图谱渲染

用仓库自带的零依赖渲染器把案例 JSON 变成单文件、可离线打开的交互式知识图谱。
**不要**手写新的可视化代码，也不要引入 d3/sigma/echarts —— 直接用下面的 CLI。

## 步骤

1. 确认输入路径。用户没给路径时，按优先级找：
   - `workers/structured-case-identification/library/cases/*.json`
   - `workers/structured-case-identification/runs/*/extraction/*/final_case.json`
2. 执行：

   ```bash
   node tools/graph-viewer/kg-render.mjs <输入.json|目录> -o artifacts/graphs/<名字>.html
   ```

   加 `--open` 直接用系统默认浏览器打开；加 `--title "自定义标题"` 覆盖标题。
   CLI 会打印节点数、关系数、类型数——把这三个数字回报给用户。
3. 需要目视确认时，用浏览器工具打开生成的 HTML 截图；确认节点、连线、图例、详情面板都正常。
4. 回报输出文件路径（markdown 链接）与统计数字。

## 输入格式

CLI 自动识别，无需预处理：

- 框架表示案例 JSON（`relationships` + 实体数组）
- `{ final_result: {...} }` / `{ result: {...} }` / `{ data: {...} }` 包裹的同结构
- JSON 数组或 `{ cases: [...] }` —— 多案例合并成一张图
- 通用图 `{ nodes: [{id,label,type}], edges|links: [{source,target,label}] }`
- 目录 —— 优先 `final_case.json`，否则合并目录下所有 `*.json`

解析失败时先用 `python3 -c "import json;print(list(json.load(open(...)).keys()))"` 看顶层键，再判断是不是需要新增适配分支。

## 改动指引

| 需求 | 改哪里 |
| --- | --- |
| 新节点类型的颜色/图标 | `tools/graph-viewer/kg-render.mjs` 的 `TYPE_META` |
| 属性字段的中文名 | 同文件的 `FIELD_LABELS` |
| 新的输入结构 | 同文件的 `unwrap` / `buildGraph` / `buildGenericGraph` |
| 布局松紧、动效、交互 | `tools/graph-viewer/kg-engine.js`（`REPULSION`/`LINK_STRENGTH`/`GRAVITY`/`draw`） |
| 页面样式、工具栏 | `tools/graph-viewer/shell.html` |

改完引擎或外壳后必须重新执行一次 CLI —— 生成的 HTML 是把两者内联进去的快照，不会自动更新。

详细说明见 `tools/graph-viewer/README.md`。
