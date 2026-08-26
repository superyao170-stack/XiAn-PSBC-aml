#!/usr/bin/env python3
"""Render case-similarity results as standalone interactive HTML."""

from __future__ import annotations

import argparse
import html
import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Dict, List, Sequence, Tuple


def clean_text(value: object) -> str:
    """Normalize a value for compact labels without matching dependencies."""
    if value is None:
        return ""
    return " ".join(str(value).split())


def _compact_label(value: object, limit: int = 11) -> str:
    """Return a one-line label; full node data is available on click."""
    text = clean_text(value)
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _matched_graph_svg(match: Dict[str, Any], side: str, graph_id: str) -> str:
    """Render a complete graph while emphasizing its matched subgraph."""
    matched = match.get("matched_subgraph") or {}
    mappings = matched.get("matched_nodes") or []
    matched_edges = matched.get("matched_edges") or []
    prefix = "query" if side == "query" else "candidate"
    other = "candidate" if side == "query" else "query"
    full_graph = matched.get(f"{prefix}_full_graph") or {}
    mapping_by_id = {item[f"{prefix}_id"]: item for item in mappings}
    node_records = []
    source_nodes = full_graph.get("nodes") or []
    if not source_nodes:
        source_nodes = [
            {
                "id": item[f"{prefix}_id"],
                "kind": item[f"{prefix}_kind"],
                "subtype": item[f"{prefix}_subtype"],
                "label": item[f"{prefix}_label"],
                "description": item.get(f"{prefix}_description", ""),
                "attributes": item.get(f"{prefix}_attributes") or {},
            }
            for item in mappings
        ]
    for node in source_nodes:
        item = mapping_by_id.get(node["id"])
        node_records.append(
            {
                **node,
                "matched": item is not None,
                "match_index": item["match_index"] if item else None,
                "side": side,
                "counterpart": (
                    {
                        "id": item[f"{other}_id"],
                        "kind": item[f"{other}_kind"],
                        "subtype": item[f"{other}_subtype"],
                        "label": item[f"{other}_label"],
                        "description": item.get(f"{other}_description", ""),
                    }
                    if item
                    else None
                ),
            }
        )

    def edge_signature(edge: Dict[str, Any]) -> Tuple[str, str, str, str]:
        return (
            str(edge.get("left") or ""),
            str(edge.get("right") or ""),
            str(edge.get("relation") or ""),
            str(edge.get("description") or ""),
        )

    matched_edge_by_signature = {
        edge_signature(item[f"{prefix}_edge"]): item for item in matched_edges
    }
    source_edges = full_graph.get("edges") or [
        item[f"{prefix}_edge"] for item in matched_edges
    ]
    edge_records = [
        {
            "edge": edge,
            "match_type": (
                matched_edge_by_signature[edge_signature(edge)]["match_type"]
                if edge_signature(edge) in matched_edge_by_signature
                else "unmatched"
            ),
        }
        for edge in source_edges
    ]

    width = 620
    max_per_row = 3
    kind_order = ("person", "entity", "event")
    kind_labels = {"person": "人员", "entity": "实体 / 账户", "event": "事件"}
    grouped = {
        kind: sorted(
            (item for item in node_records if item["kind"] == kind),
            key=lambda item: (
                item["match_index"] is None,
                item["match_index"] or 0,
                item["id"],
            ),
        )
        for kind in kind_order
    }
    positions: Dict[str, Tuple[float, float]] = {}
    lane_top_by_node: Dict[str, float] = {}
    lanes = []
    current_y = 20
    for kind in kind_order:
        items = grouped[kind]
        if not items:
            continue
        rows = [items[index : index + max_per_row] for index in range(0, len(items), max_per_row)]
        # Reserve enough space above same-row nodes for curved relations and
        # their labels. This prevents an event card from covering its edge tag.
        lane_height = 112 + len(rows) * 112
        lanes.append((kind, current_y, lane_height))
        for row_index, row in enumerate(rows):
            y = current_y + 152 + row_index * 112
            gap = (width - 52) / (len(row) + 1)
            for item_index, item in enumerate(row, start=1):
                positions[item["id"]] = (26 + gap * item_index, y)
                lane_top_by_node[item["id"]] = current_y
        current_y += lane_height + 12
    height = max(210, current_y + 8)

    connections: Dict[str, List[dict]] = defaultdict(list)
    for edge_item in edge_records:
        edge = edge_item["edge"]
        connections[edge["left"]].append(
            {"node_id": edge["right"], "relation": edge["relation"], "match_type": edge_item["match_type"]}
        )
        connections[edge["right"]].append(
            {"node_id": edge["left"], "relation": edge["relation"], "match_type": edge_item["match_type"]}
        )

    pattern_id = f"mesh-{graph_id}"
    parts = [
        f'<svg viewBox="0 0 {width} {height}" role="img" aria-label="{html.escape(side)} matched graph">',
        f'<defs><pattern id="{pattern_id}" width="18" height="18" patternUnits="userSpaceOnUse">'
        '<path d="M18 0H0V18" fill="none" stroke="#d9e2ef" stroke-width=".7" opacity=".45"/></pattern></defs>',
        f'<rect width="{width}" height="{height}" fill="url(#{pattern_id})"/>',
    ]
    for kind, lane_y, lane_height in lanes:
        parts.append(
            f'<rect class="lane lane-{kind}" x="14" y="{lane_y}" width="592" height="{lane_height}" rx="16"/>'
            f'<text class="lane-label" x="30" y="{lane_y + 24}">{kind_labels[kind]}</text>'
        )

    edge_paths = []
    edge_chips = []
    for edge_index, edge_item in enumerate(edge_records):
        edge = edge_item["edge"]
        if edge["left"] not in positions or edge["right"] not in positions:
            continue
        x1, y1 = positions[edge["left"]]
        x2, y2 = positions[edge["right"]]
        midpoint_x, midpoint_y = (x1 + x2) / 2, (y1 + y2) / 2
        same_lane = abs(y1 - y2) < 5
        control_x = midpoint_x
        if same_lane:
            lane_top = min(
                lane_top_by_node.get(edge["left"], midpoint_y),
                lane_top_by_node.get(edge["right"], midpoint_y),
            )
            # A dedicated relation rail sits above the node cards. The label
            # no longer follows the curve midpoint, so dense event rows cannot
            # cover it regardless of node selection or paint effects.
            control_y = lane_top + 38 + (edge_index % 2) * 18
            label_y = lane_top + 72 + (edge_index % 2) * 18
        else:
            control_y = midpoint_y
            label_y = midpoint_y
        state = edge_item["match_type"]
        edge_paths.append(
            f'<path class="graph-edge {state}" d="M{x1},{y1} Q{control_x},{control_y} {x2},{y2}"/>'
        )
        edge_chips.append(
            f'<g class="edge-chip {state}" transform="translate({midpoint_x},{label_y})">'
            '<rect x="-38" y="-11" width="76" height="22" rx="11"/>'
            f'<text text-anchor="middle" y="4">{html.escape(edge["relation"])}</text></g>'
        )
    parts.extend(edge_paths)

    icon_by_kind = {"person": "人", "entity": "实", "event": "事"}
    for item in node_records:
        x, y = positions[item["id"]]
        item["connections"] = connections.get(item["id"], [])
        node_data = html.escape(
            json.dumps(item, ensure_ascii=False, separators=(",", ":")),
            quote=True,
        )
        aria = html.escape(f'查看节点 {item["label"] or item["id"]} 的属性', quote=True)
        node_state = "matched" if item["matched"] else "unmatched"
        badge = (
            f'<g class="match-badge" transform="translate(59,-20)"><circle r="13"/>'
            f'<text text-anchor="middle" y="4">{item["match_index"]}</text></g>'
            if item["matched"]
            else ""
        )
        match_index = item["match_index"] if item["matched"] else ""
        parts.append(
            f'<g class="graph-node kind-{item["kind"]} {node_state}" transform="translate({x},{y})" '
            f'role="button" tabindex="0" aria-pressed="false" aria-label="{aria}" '
            f'data-match-index="{match_index}" data-node="{node_data}">'
            '<rect class="node-body" x="-72" y="-28" width="144" height="56" rx="16"/>'
            '<circle class="node-icon" cx="-50" cy="0" r="15"/>'
            f'<text class="node-icon-text" x="-50" y="5" text-anchor="middle">{icon_by_kind.get(item["kind"], "点")}</text>'
            f'<text class="node-label" x="-28" y="5">{html.escape(_compact_label(item["label"] or item["id"]))}</text>'
            f'{badge}'
            '</g>'
        )
    # Edge labels are intentionally appended after nodes so their text remains
    # readable even when a future graph contains unusually dense geometry.
    parts.extend(edge_chips)
    if not node_records:
        parts.append('<text x="310" y="105" text-anchor="middle" class="empty">案例图中没有节点</text>')
    parts.append("</svg>")
    return "".join(parts)


def _highlight_event_description(
    description: object,
    common_points: Sequence[dict],
    side: str,
) -> str:
    """Escape an event description and highlight complete matched clauses."""
    text = str(description or "—")
    field_name = "query_text" if side == "query" else "candidate_text"
    intervals = []
    for index, point in enumerate(common_points, start=1):
        fragment = str(point.get(field_name) or "")
        if not fragment:
            continue
        start = text.find(fragment)
        if start >= 0:
            intervals.append((start, start + len(fragment), index))

    rendered = []
    cursor = 0
    for start, end, index in sorted(intervals):
        if start < cursor:
            continue
        rendered.append(html.escape(text[cursor:start]))
        rendered.append(
            f'<mark class="similarity-highlight point-{index}" '
            f'title="相似点 {index}">{html.escape(text[start:end])}</mark>'
        )
        cursor = end
    rendered.append(html.escape(text[cursor:]))
    return "".join(rendered)


def write_similarity_visualization(payload: Dict[str, Any], path: Path) -> None:
    """Write an interactive HTML view for a query's Top-5 matches."""
    query = payload["query_case"]
    results = payload.get("results") or []
    overview_rows = []
    cards = []
    for result in results:
        matched = result.get("matched_subgraph") or {}
        overview_rows.append(
            "<tr>"
            f'<td>{result["rank"]}</td>'
            f'<td><a href="#rank-{result["rank"]}">{html.escape(result["case_name"])}</a></td>'
            f'<td>{result["similarity"]:.6f}</td>'
            f'<td>{matched.get("matched_node_count", 0)}</td>'
            f'<td>{matched.get("matched_edge_count", 0)}</td>'
            "</tr>"
        )
        edge_rows = []
        for item in matched.get("matched_edges") or []:
            left_edge = item["query_edge"]
            right_edge = item["candidate_edge"]
            state = "保持" if item["match_type"] == "preserved" else "替换"
            edge_rows.append(
                "<tr>"
                f'<td>{html.escape(left_edge["left"])} — {html.escape(left_edge["relation"])} — {html.escape(left_edge["right"])}</td>'
                f'<td>{html.escape(right_edge["left"])} — {html.escape(right_edge["relation"])} — {html.escape(right_edge["right"])}</td>'
                f'<td><span class="state {item["match_type"]}">{state}</span></td>'
                "</tr>"
            )
        edge_table = (
            '<details class="edge-details"><summary>查看命中边明细</summary>'
            '<div class="table-scroll"><table class="edge-table"><thead><tr><th>新增案例边</th><th>相似案例边</th><th>状态</th></tr></thead>'
            f'<tbody>{"".join(edge_rows)}</tbody></table></div></details>'
            if edge_rows
            else '<p class="no-edges">本组没有同时命中的边</p>'
        )
        event_summary = result.get("event_similarity_summary") or {}
        anchor_cards = []
        for anchor_index, point in enumerate(
            event_summary.get("similar_points") or [],
            start=1,
        ):
            level = point.get("anchor_level") or "candidate"
            level_label = "高置信度" if level == "high" else "普通候选"
            query_event = point.get("query_event") or {}
            candidate_event = point.get("candidate_event") or {}
            common_points = point.get("common_description_points") or []
            query_description = _highlight_event_description(
                query_event.get("event_description"),
                common_points,
                "query",
            )
            candidate_description = _highlight_event_description(
                candidate_event.get("event_description"),
                common_points,
                "candidate",
            )
            highlight_legend = "".join(
                f'<span><i class="point-{index}"></i>相似点 {index}</span>'
                for index in range(1, len(common_points) + 1)
            )
            anchor_cards.append(
                '<article class="event-anchor-card">'
                '<div class="event-anchor-head">'
                f'<span class="anchor-number">锚点 {anchor_index}</span>'
                f'<span class="anchor-level {html.escape(level)}">{level_label}</span>'
                f'<span class="anchor-score">描述相似度 {float(point.get("description_similarity") or 0):.4f}</span>'
                '</div>'
                '<section class="event-detail-block">'
                '<h4><span>01</span>事件与事件描述</h4>'
                + (
                    f'<div class="highlight-legend"><b>高亮相似内容</b>{highlight_legend}</div>'
                    if highlight_legend
                    else '<div class="highlight-legend muted">未提取到可高亮的完整共同分句</div>'
                )
                +
                '<div class="event-compare-grid">'
                '<article class="event-side query-side"><div class="event-side-label">查询事件</div>'
                f'<h5>{html.escape(str(query_event.get("event_name") or point.get("query_event_id") or "—"))}</h5>'
                f'<div class="event-type">{html.escape(str(query_event.get("event_type") or point.get("event_type") or "—"))}</div>'
                f'<p>{query_description}</p></article>'
                '<article class="event-side candidate-side"><div class="event-side-label">候选事件</div>'
                f'<h5>{html.escape(str(candidate_event.get("event_name") or point.get("candidate_event_id") or "—"))}</h5>'
                f'<div class="event-type">{html.escape(str(candidate_event.get("event_type") or point.get("event_type") or "—"))}</div>'
                f'<p>{candidate_description}</p></article>'
                '</div></section>'
                '</article>'
            )
        anchor_count = int(event_summary.get("anchor_count") or 0)
        event_similarity_panel = (
            '<section class="event-similarity-panel">'
            '<div class="event-similarity-head">'
            '<div><span class="section-eyebrow">EVENT SIMILARITY</span>'
            '<h3>事件之间的相似点</h3></div>'
            f'<span class="anchor-total">{anchor_count} 个锚点</span>'
            '</div>'
            f'<p class="event-similarity-summary">{html.escape(str(event_summary.get("summary") or "暂无事件锚点摘要"))}</p>'
            f'<div class="event-anchor-list">{"".join(anchor_cards)}</div>'
            + (
                ''
                if anchor_cards
                else '<div class="event-similarity-empty">当前案例未形成满足阈值的事件锚点。</div>'
            )
            + '</section>'
        )
        graph_prefix = f'rank-{result["rank"]}'
        cards.append(
            f'<section class="match-card" id="rank-{result["rank"]}">'
            f'<div class="match-head"><div><span class="rank">#{result["rank"]}</span>'
            f'<h2>{html.escape(result["case_name"])}</h2><code>{html.escape(result["case_id"])}</code></div>'
            f'<div class="score"><small>相似度</small><strong>{result["similarity"]:.6f}</strong>'
            f'<span>GED {result["normalized_ged"]:.6f}</span></div></div>'
            '<div class="graphs">'
            f'<div class="graph-panel"><div class="graph-title"><h3>新增案例</h3><span>{html.escape(query["case_name"])}</span></div>{_matched_graph_svg(result, "query", graph_prefix + "-q")}</div>'
            f'<div class="graph-panel"><div class="graph-title"><h3>相似案例</h3><span>{html.escape(result["case_name"])}</span></div>{_matched_graph_svg(result, "candidate", graph_prefix + "-c")}</div>'
            '</div>'
            f'{event_similarity_panel}'
            '<div class="node-inspector" hidden aria-live="polite">'
            '<div class="inspector-head"><div><span class="inspector-eyebrow">NODE DETAILS</span><h3 data-node-title>节点详情</h3></div>'
            '<button type="button" class="inspector-close" data-close-inspector aria-label="关闭节点详情">×</button></div>'
            '<div class="inspector-content" data-node-content></div></div>'
            f'<div class="counts"><span>命中节点 <strong>{matched.get("matched_node_count", 0)}</strong></span>'
            f'<span>命中边 <strong>{matched.get("matched_edge_count", 0)}</strong></span>'
            '<span class="click-tip">点击节点查看数据与属性</span></div>'
            f'{edge_table}</section>'
        )

    document = f'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(query["case_name"])} · 相似案例命中子图</title>
<style>
:root{{--bg:#f3f6fb;--ink:#172033;--muted:#667085;--line:#d9e2ee;--card:#fff;--blue:#2563eb;--purple:#7c3aed;--amber:#d97706;--green:#16a34a;--orange:#ea580c}}
*{{box-sizing:border-box}} body{{margin:0;background:linear-gradient(180deg,#eef3fa 0,#f8fafc 360px);color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","Microsoft YaHei",sans-serif}}
main{{max-width:1320px;margin:0 auto;padding:32px 24px 64px}} header{{padding:30px 34px;background:linear-gradient(130deg,#102f52,#1f5d9d 68%,#2874bd);color:#fff;border-radius:22px;box-shadow:0 18px 44px #17385b22}}
h1{{margin:6px 0 9px;font-size:28px}} header p{{margin:0;opacity:.82}} .eyebrow,.inspector-eyebrow{{font-size:11px;letter-spacing:.14em;opacity:.7}}
.legend{{display:flex;gap:16px;flex-wrap:wrap;margin:20px 2px 10px;font-size:13px;color:#475467}} .dot{{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:6px}}
.summary,.match-card{{background:rgba(255,255,255,.96);border:1px solid var(--line);border-radius:20px;margin-top:22px;box-shadow:0 10px 30px #1720330b}}
.summary{{padding:16px 22px}} table{{width:100%;border-collapse:collapse}} th,td{{padding:11px 12px;text-align:left;border-bottom:1px solid #edf1f5;font-size:14px}} th{{color:var(--muted);font-weight:600}} a{{color:#225ea8;text-decoration:none}}
.match-card{{padding:24px;scroll-margin-top:18px}} .match-head{{display:flex;justify-content:space-between;gap:20px;align-items:center}} .match-head h2{{display:inline;margin:0 10px;font-size:20px}} .rank{{background:#e8f0fa;color:#174b82;padding:5px 9px;border-radius:8px;font-weight:700}} code{{color:var(--muted)}}
.score{{text-align:right;display:grid;gap:2px}} .score small,.score span{{color:var(--muted)}} .score strong{{font-size:24px;color:#174b82}}
.graphs{{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:20px}} .graph-panel{{border:1px solid #d5dfec;border-radius:18px;overflow:hidden;background:#f8fbff;box-shadow:inset 0 1px #fff}} .graph-title{{display:flex;align-items:baseline;gap:10px;padding:14px 18px;background:linear-gradient(90deg,#edf4fc,#f8fbff);border-bottom:1px solid #dce5f0}} .graph-title h3{{margin:0;font-size:14px;color:#174b82}} .graph-title span{{font-size:12px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}} svg{{display:block;width:100%;height:auto}}
.lane{{fill:#fff;stroke:#dfe7f1;stroke-width:1}} .lane-person{{fill:#f4f8ff}} .lane-entity{{fill:#f8f5ff}} .lane-event{{fill:#fffbf2}} .lane-label{{font-size:11px;font-weight:700;fill:#8492a6;letter-spacing:.08em}}
.graph-edge{{fill:none;stroke-width:3;stroke-linecap:round;opacity:.76}} .graph-edge.preserved{{stroke:var(--green)}} .graph-edge.substituted{{stroke:var(--orange)}} .graph-edge.unmatched{{stroke:#aeb8c5;stroke-width:2;opacity:.34;stroke-dasharray:5 5}} .edge-chip{{pointer-events:none}} .edge-chip rect{{fill:#fff;stroke-width:1.5}} .edge-chip.preserved rect{{stroke:var(--green)}} .edge-chip.substituted rect{{stroke:var(--orange)}} .edge-chip.unmatched{{opacity:.42}} .edge-chip.unmatched rect{{stroke:#aeb8c5}} .edge-chip text{{font-size:10px;fill:#344054}}
.graph-node{{cursor:pointer;outline:none}} .graph-node.unmatched{{opacity:.38}} .graph-node.unmatched:hover,.graph-node.unmatched:focus,.graph-node.unmatched.is-selected{{opacity:.88}} .node-body{{stroke-width:1.5;transition:filter .18s,stroke-width .18s,transform .18s}} .kind-person .node-body{{fill:#eef5ff;stroke:#8bb9f8}} .kind-entity .node-body{{fill:#f5efff;stroke:#b69aef}} .kind-event .node-body{{fill:#fff5dc;stroke:#efbd5e}} .node-icon{{fill:#fff}} .kind-person .node-icon{{stroke:var(--blue);stroke-width:2}} .kind-entity .node-icon{{stroke:var(--purple);stroke-width:2}} .kind-event .node-icon{{stroke:var(--amber);stroke-width:2}} .node-icon-text{{font-size:11px;font-weight:700;fill:#344054}} .node-label{{font-size:12px;font-weight:650;fill:#172033}} .match-badge circle{{fill:#173f69}} .match-badge text{{font-size:10px;font-weight:700;fill:#fff}} .graph-node:hover .node-body,.graph-node:focus .node-body{{filter:drop-shadow(0 6px 8px #25466b2b);stroke-width:2.5}} .graph-node.is-selected .node-body{{stroke:#0d3157;stroke-width:3;filter:drop-shadow(0 7px 10px #153c6638)}} .empty{{fill:#98a2b3}}
.event-similarity-panel{{margin-top:18px;border:1px solid #d9e3f0;border-radius:18px;background:linear-gradient(135deg,#f7faff 0%,#fffaf1 100%);padding:20px;box-shadow:inset 0 1px #fff}} .event-similarity-head{{display:flex;justify-content:space-between;align-items:center;gap:16px}} .event-similarity-head h3{{margin:4px 0 0;font-size:18px;color:#173f69}} .section-eyebrow{{font-size:10px;letter-spacing:.14em;color:#8090a3}} .anchor-total{{padding:6px 11px;border-radius:999px;background:#e8f0fa;color:#174b82;font-size:12px;font-weight:700}} .event-similarity-summary{{margin:12px 0 0;color:#536273;font-size:13px}} .event-anchor-list{{display:grid;gap:12px;margin-top:15px}} .event-anchor-card{{border:1px solid #dde5ee;border-left:4px solid #d99a2b;border-radius:14px;background:rgba(255,255,255,.94);overflow:hidden}} .event-anchor-head{{display:flex;align-items:center;gap:9px;flex-wrap:wrap;padding:11px 14px;background:#fbfcfe;border-bottom:1px solid #edf1f5}} .anchor-number{{font-weight:700;color:#26384d}} .anchor-level{{font-size:11px;padding:3px 8px;border-radius:999px;background:#fff3d8;color:#9a5b00}} .anchor-level.high{{background:#e8f7ee;color:#087b34}} .anchor-score{{margin-left:auto;color:#667085;font-size:12px}} .event-anchor-body{{padding:14px 16px;color:#344054;font-size:13px;line-height:1.82;word-break:break-word}} .event-similarity-empty{{margin-top:14px;padding:14px;border:1px dashed #cfd9e5;border-radius:12px;color:#8492a6;text-align:center;background:#fff}}
.event-detail-block{{padding:17px}} .event-detail-block h4{{display:flex;align-items:center;gap:9px;margin:0 0 10px;color:#26384d;font-size:14px}} .event-detail-block h4 span{{display:inline-grid;place-items:center;width:25px;height:25px;border-radius:8px;background:#e8f0fa;color:#174b82;font-size:10px}} .highlight-legend{{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:0 0 13px;padding:8px 10px;border:1px solid #e1e8f0;border-radius:10px;background:#f8fafc;color:#667085;font-size:11px}} .highlight-legend b{{color:#344054}} .highlight-legend span{{display:inline-flex;align-items:center;gap:5px}} .highlight-legend i{{display:inline-block;width:11px;height:11px;border-radius:3px;border:1px solid transparent}} .highlight-legend.muted{{color:#8492a6}} .highlight-legend .point-1,.similarity-highlight.point-1{{background:#ffe59a;border-color:#d9a72e}} .highlight-legend .point-2,.similarity-highlight.point-2{{background:#ccefdc;border-color:#39a66c}} .highlight-legend .point-3,.similarity-highlight.point-3{{background:#dce7ff;border-color:#6688d8}} .similarity-highlight{{padding:1px 3px;border:1px solid;border-radius:4px;color:inherit;font-weight:600;box-decoration-break:clone;-webkit-box-decoration-break:clone}} .event-compare-grid{{display:grid;grid-template-columns:1fr 1fr;gap:13px}} .event-side{{border:1px solid #dce5ef;border-radius:13px;padding:14px;background:#fff;min-width:0}} .query-side{{border-top:3px solid #2563eb}} .candidate-side{{border-top:3px solid #7c3aed}} .event-side-label{{font-size:11px;font-weight:700;letter-spacing:.08em;color:#667085}} .query-side .event-side-label{{color:#1d5ec5}} .candidate-side .event-side-label{{color:#6d3ac5}} .event-side h5{{margin:7px 0 8px;font-size:14px;line-height:1.45;color:#1d2939}} .event-type{{display:inline-block;padding:3px 8px;border-radius:999px;background:#f0f4f8;color:#475467;font-size:11px}} .event-side p{{margin:11px 0 0;color:#475467;font-size:12px;line-height:1.72;word-break:break-word}}
.node-inspector{{margin-top:18px;border:1px solid #cbd9e8;border-left:5px solid #1f5d9d;border-radius:16px;background:linear-gradient(120deg,#f8fbff,#fff);padding:18px 20px}} .node-inspector[hidden]{{display:none}} .inspector-head{{display:flex;align-items:flex-start;justify-content:space-between;gap:20px}} .inspector-head h3{{margin:4px 0 0;font-size:18px}} .inspector-close{{border:0;background:#eaf0f7;color:#28445f;width:30px;height:30px;border-radius:50%;font-size:20px;cursor:pointer}} .inspector-content{{display:grid;grid-template-columns:minmax(230px,.8fr) 1.4fr;gap:20px;margin-top:14px}} .pair-panel,.attribute-panel{{border:1px solid #e0e7ef;border-radius:13px;background:#fff;padding:15px}} .pair-arrow{{color:#7b8794;margin:10px 0}} .pair-name{{font-weight:700;margin:4px 0}} .pair-id{{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:var(--muted);word-break:break-all}} .node-kind{{display:inline-block;padding:3px 8px;border-radius:999px;background:#eaf1f8;color:#31506e;font-size:11px}} .attribute-panel h4{{margin:0 0 10px;font-size:14px}} .attribute-list{{display:grid;grid-template-columns:minmax(110px,.35fr) 1fr;margin:0}} .attribute-list dt,.attribute-list dd{{margin:0;padding:8px 6px;border-bottom:1px solid #eef2f6;font-size:13px;word-break:break-word}} .attribute-list dt{{color:var(--muted)}} .connections{{margin:12px 0 0;padding-left:18px;color:#475467;font-size:12px}}
.counts{{display:flex;align-items:center;gap:18px;margin:16px 2px 5px;color:var(--muted);font-size:13px}} .click-tip{{margin-left:auto;color:#245b92}} .edge-details{{margin-top:10px;border-top:1px solid #edf1f5;padding-top:10px}} .edge-details summary{{cursor:pointer;color:#315f8c;font-size:13px}} .edge-table{{margin-top:8px}} .edge-table td{{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}} .state{{display:inline-block;padding:3px 8px;border-radius:999px;font-family:inherit}} .state.preserved{{color:#087b34;background:#e7f7ed}} .state.substituted{{color:#b54708;background:#fff1e7}} .no-edges{{margin:12px 2px 0;color:#98a2b3;font-size:12px}}
@media(max-width:900px){{.graphs{{grid-template-columns:1fr}}.inspector-content{{grid-template-columns:1fr}}.match-head{{align-items:flex-start}}main{{padding:20px 12px 40px}}}}
@media(max-width:720px){{.event-compare-grid{{grid-template-columns:1fr}}}}
@media(max-width:560px){{.match-head{{display:block}}.score{{text-align:left;margin-top:14px}}.summary{{overflow-x:auto}}.counts{{flex-wrap:wrap}}.click-tip{{width:100%;margin-left:0}}}}
@media print{{body{{background:white}}main{{max-width:none;padding:0}}.match-card{{break-inside:avoid;box-shadow:none}}header{{box-shadow:none}}}}
</style></head><body><main>
<header><div class="eyebrow">Case Similarity · Complete Graphs</div><h1>{html.escape(query["case_name"])}</h1><p>{html.escape(query["case_id"])} · Top-{len(results)} 相似案例（事件锚点详见各项）</p></header>
<div class="legend"><span><i class="dot" style="background:#2563eb"></i>人员</span><span><i class="dot" style="background:#7c3aed"></i>实体/账户</span><span><i class="dot" style="background:#d97706"></i>事件</span><span><i class="dot" style="background:#16a34a"></i>关系保持</span><span><i class="dot" style="background:#ea580c"></i>关系替换</span><span><i class="dot" style="background:#b6bec9"></i>未匹配内容（半透明）</span><span>节点圆点编号表示两侧配对</span></div>
<section class="summary"><table><thead><tr><th>排名</th><th>相似案例</th><th>相似度</th><th>命中节点</th><th>命中边</th></tr></thead><tbody>{''.join(overview_rows)}</tbody></table></section>
{''.join(cards)}
</main><script>
(() => {{
  const kindNames = {{person: "人员", entity: "实体 / 账户", event: "事件"}};
  const readable = value => {{
    if (value === null || value === undefined || value === "") return "—";
    if (typeof value === "object") return JSON.stringify(value, null, 2);
    return String(value);
  }};
  const addRow = (list, label, value) => {{
    const dt = document.createElement("dt"); dt.textContent = label;
    const dd = document.createElement("dd"); dd.textContent = readable(value);
    list.append(dt, dd);
  }};
  const showNode = nodeElement => {{
    const card = nodeElement.closest(".match-card");
    const data = JSON.parse(nodeElement.dataset.node);
    card.querySelectorAll(".graph-node").forEach(item => {{
      const selected = data.match_index === null
        ? item === nodeElement
        : item.dataset.matchIndex === String(data.match_index);
      item.classList.toggle("is-selected", selected);
      item.setAttribute("aria-pressed", selected ? "true" : "false");
    }});
    const inspector = card.querySelector(".node-inspector");
    inspector.hidden = false;
    inspector.querySelector("[data-node-title]").textContent = data.label || data.id;
    const content = inspector.querySelector("[data-node-content]");
    content.replaceChildren();

    const pair = document.createElement("section"); pair.className = "pair-panel";
    const sourceTag = document.createElement("span"); sourceTag.className = "node-kind";
    sourceTag.textContent = (data.side === "query" ? "新增案例 · " : "相似案例 · ") + (kindNames[data.kind] || data.kind);
    const sourceName = document.createElement("div"); sourceName.className = "pair-name"; sourceName.textContent = data.label || data.id;
    const sourceId = document.createElement("div"); sourceId.className = "pair-id"; sourceId.textContent = data.id;
    pair.append(sourceTag, sourceName, sourceId);
    const arrow = document.createElement("div"); arrow.className = "pair-arrow";
    if (data.counterpart) {{
      arrow.textContent = "匹配到  →";
      const targetTag = document.createElement("span"); targetTag.className = "node-kind"; targetTag.textContent = kindNames[data.counterpart.kind] || data.counterpart.kind;
      const targetName = document.createElement("div"); targetName.className = "pair-name"; targetName.textContent = data.counterpart.label || data.counterpart.id;
      const targetId = document.createElement("div"); targetId.className = "pair-id"; targetId.textContent = data.counterpart.id;
      pair.append(arrow, targetTag, targetName, targetId);
    }} else {{
      arrow.textContent = "未与另一侧节点匹配";
      pair.append(arrow);
    }}
    if (data.connections.length) {{
      const connections = document.createElement("ul"); connections.className = "connections";
      data.connections.forEach(connection => {{
        const item = document.createElement("li");
        const relationState = connection.match_type === "preserved"
          ? "关系保持"
          : connection.match_type === "substituted" ? "关系替换" : "未匹配关系";
        item.textContent = `${{connection.relation}} → ${{connection.node_id}}（${{relationState}}）`;
        connections.append(item);
      }});
      pair.append(connections);
    }}

    const attributes = document.createElement("section"); attributes.className = "attribute-panel";
    const heading = document.createElement("h4"); heading.textContent = "节点数据与属性";
    const list = document.createElement("dl"); list.className = "attribute-list";
    addRow(list, "节点 ID", data.id); addRow(list, "节点类型", kindNames[data.kind] || data.kind);
    addRow(list, "子类型", data.subtype); addRow(list, "名称", data.label); addRow(list, "描述", data.description);
    Object.entries(data.attributes || {{}}).forEach(([key, value]) => addRow(list, key, value));
    attributes.append(heading, list); content.append(pair, attributes);
    inspector.scrollIntoView({{behavior: "smooth", block: "nearest"}});
  }};
  document.addEventListener("click", event => {{
    const node = event.target.closest(".graph-node");
    if (node) {{ showNode(node); return; }}
    const close = event.target.closest("[data-close-inspector]");
    if (close) {{
      const card = close.closest(".match-card"); card.querySelector(".node-inspector").hidden = true;
      card.querySelectorAll(".graph-node").forEach(item => {{item.classList.remove("is-selected"); item.setAttribute("aria-pressed", "false");}});
    }}
  }});
  document.addEventListener("keydown", event => {{
    const node = event.target.closest(".graph-node");
    if (node && (event.key === "Enter" || event.key === " ")) {{event.preventDefault(); showNode(node);}}
  }});
}})();
</script></body></html>'''
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(document, encoding="utf-8")


def discover_similarity_results(input_path: Path) -> List[Path]:
    """Find one result JSON or all per-case result JSON files in a directory."""
    input_path = Path(input_path)
    if input_path.is_file():
        return [input_path]
    if input_path.is_dir():
        return sorted(input_path.glob("*_similarity.json"))
    raise FileNotFoundError(f"相似度结果不存在: {input_path}")


def generate_visualizations(
    input_path: Path,
    output: Path | None = None,
    output_dir: Path | None = None,
) -> List[Path]:
    """Generate HTML files from already-calculated similarity JSON results."""
    result_paths = discover_similarity_results(input_path)
    if not result_paths:
        raise ValueError(f"未找到 *_similarity.json: {input_path}")
    if output is not None and len(result_paths) != 1:
        raise ValueError("--output 只能与单个相似度结果 JSON 一起使用")

    generated: List[Path] = []
    for result_path in result_paths:
        payload = json.loads(result_path.read_text(encoding="utf-8-sig"))
        if not isinstance(payload, dict) or not isinstance(payload.get("results"), list):
            raise ValueError(f"不是有效的相似度结果 JSON: {result_path}")
        stem = result_path.stem.removesuffix("_similarity")
        target = (
            Path(output)
            if output is not None
            else (Path(output_dir) if output_dir is not None else result_path.parent)
            / f"{stem}_matched_graphs.html"
        )
        write_similarity_visualization(payload, target)
        generated.append(target.resolve())
    return generated


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="将相似度匹配结果 JSON 生成为交互式 HTML 可视化。"
    )
    parser.add_argument(
        "--input",
        required=True,
        help="单个 *_similarity.json 文件，或包含这些文件的结果目录",
    )
    parser.add_argument("--output", help="单文件模式下指定 HTML 输出路径")
    parser.add_argument(
        "--output-dir",
        help="指定批量 HTML 输出目录；默认与输入 JSON 位于同一目录",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    paths = generate_visualizations(
        Path(args.input),
        Path(args.output) if args.output else None,
        Path(args.output_dir) if args.output_dir else None,
    )
    print(json.dumps(
        {
            "generated_count": len(paths),
            "visualization_files": [str(path) for path in paths],
        },
        ensure_ascii=False,
        indent=2,
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
