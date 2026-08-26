"""Deterministic analytics service for reproducible risk experiments.

The service intentionally implements transparent statistical baselines.  More
advanced models can adopt the same request/response contract without changing
the bank application or its experiment lineage.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import statistics
from collections import Counter, defaultdict
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any


VERSION = "1.5.2"


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def parse_time(value: str) -> datetime:
    normalized = value.replace("Z", "+00:00")
    result = datetime.fromisoformat(normalized)
    return result if result.tzinfo else result.replace(tzinfo=timezone.utc)


def clamp(value: float, lower: float = 0.0, upper: float = 1.0) -> float:
    return max(lower, min(upper, value))


def population_stddev(values: list[float]) -> float:
    return statistics.pstdev(values) if len(values) > 1 else 0.0


def normalized_entropy(values: list[str]) -> float:
    if len(values) < 2:
        return 0.0
    counts = Counter(values)
    entropy = -sum((count / len(values)) * math.log2(count / len(values))
                   for count in counts.values())
    maximum = math.log2(len(counts)) if len(counts) > 1 else 1.0
    return round(entropy / maximum, 8) if maximum else 0.0


def sequence_similarity(left: list[str], right: list[str]) -> float:
    if not left and not right:
        return 1.0
    if not left or not right:
        return 0.0
    previous = list(range(len(right) + 1))
    for index, left_value in enumerate(left, start=1):
        current = [index]
        for right_index, right_value in enumerate(right, start=1):
            current.append(min(
                current[-1] + 1,
                previous[right_index] + 1,
                previous[right_index - 1] + (left_value != right_value),
            ))
        previous = current
    return round(1.0 - previous[-1] / max(len(left), len(right)), 8)


def event_subject(event: dict[str, Any]) -> str:
    direct = str(event.get("subjectId", "")).strip()
    if direct:
        return direct
    participants = event.get("participants", [])
    preferred = [
        item for item in participants
        if str(item.get("role", "")).upper() in {"SUBJECT", "ACTOR", "SOURCE_ACCOUNT"}
    ]
    selected = preferred[0] if preferred else (participants[0] if participants else {})
    return str(selected.get("entityUid", "")).strip()


def event_type(event: dict[str, Any]) -> str:
    direct = str(event.get("eventType", "")).strip()
    if direct:
        return direct
    action = event.get("action", {})
    return str(action.get("code") or event.get("standardEventCode") or "").strip()


def event_time(event: dict[str, Any]) -> str:
    occurred_at = event.get("occurredAt", "")
    if isinstance(occurred_at, dict):
        return str(occurred_at.get("start", "")).strip()
    return str(occurred_at).strip()


def feature_snapshot(payload: dict[str, Any]) -> dict[str, Any]:
    records = payload.get("records", [])
    numeric: dict[str, list[float]] = defaultdict(list)
    for record in records:
        for key, value in record.items():
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                numeric[key].append(float(value))
    summary = {
        key: {
            "count": len(values),
            "min": min(values),
            "max": max(values),
            "mean": statistics.fmean(values),
        }
        for key, values in sorted(numeric.items()) if values
    }
    return {
        "snapshotSha256": digest(payload),
        "recordCount": len(records),
        "featureSummary": summary,
        "schemaVersion": payload.get("schemaVersion"),
    }


def mine_event_chains(payload: dict[str, Any]) -> dict[str, Any]:
    events = payload.get("events", [])
    min_confidence = float(payload.get("minConfidence", 0.0))
    min_support = float(payload.get("minSupport", 0.0))
    max_gap_seconds = float(payload.get("maxGapSeconds", 30 * 24 * 3600))
    by_subject: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        subject = event_subject(event)
        event_type_value = event_type(event)
        occurred_at = event_time(event)
        confidence = float(event.get("confidence", 1.0))
        if subject and event_type_value and occurred_at and confidence >= min_confidence:
            by_subject[subject].append(event)

    chains: list[dict[str, Any]] = []
    for subject, subject_events in sorted(by_subject.items()):
        ordered = sorted(subject_events, key=lambda item: parse_time(event_time(item)))
        segments: list[list[dict[str, Any]]] = [[]]
        for event in ordered:
            if segments[-1]:
                gap = (parse_time(event_time(event))
                       - parse_time(event_time(segments[-1][-1]))).total_seconds()
                if gap > max_gap_seconds:
                    segments.append([])
            segments[-1].append(event)
        for segment_index, segment in enumerate(segments):
            sequence = [event_type(item) for item in segment]
            chains.append({
                "chainId": "CHAIN-" + digest({
                    "subjectId": subject, "segment": segment_index, "events": segment})[:24],
                "subjectId": subject,
                "eventTypes": sequence,
                "eventIds": [item.get("eventId") for item in segment],
                "startedAt": event_time(segment[0]),
                "endedAt": event_time(segment[-1]),
                "eventCount": len(segment),
                "eventTypeEntropy": normalized_entropy(sequence),
                "chainSha256": digest({"subjectId": subject, "events": segment}),
            })

    cooccurrence = behavior_matrix({
        "events": events,
        "minConfidence": min_confidence,
        "windowSeconds": max_gap_seconds,
    })
    sequence_counts = Counter(tuple(chain["eventTypes"]) for chain in chains)
    theme_rules = payload.get("themeRules", {})
    templates = []
    for sequence, count in sorted(sequence_counts.items(), key=lambda item: (-item[1], item[0])):
        support = count / max(1, len(chains))
        if support < min_support:
            continue
        theme_scores = {
            str(theme): len(set(sequence) & {str(value) for value in required}) / max(1, len(required))
            for theme, required in theme_rules.items() if isinstance(required, list)
        }
        theme = max(theme_scores, key=lambda value: (theme_scores[value], value)) if theme_scores else "UNCLASSIFIED"
        confidence = clamp(support)
        templates.append({
            "templateId": "ECT-" + digest({"sequence": sequence, "theme": theme})[:24],
            "theme": theme,
            "eventTypes": list(sequence),
            "occurrenceCount": count,
            "support": round(support, 8),
            "confidence": round(confidence, 8),
            "sequenceEntropy": normalized_entropy(list(sequence)),
            "templateSha256": digest({"sequence": sequence, "theme": theme}),
        })
    return {
        "inputSnapshotSha256": digest(payload),
        "chainCount": len(chains),
        "chains": chains,
        "templates": templates,
        "behaviorPairs": cooccurrence["relations"],
        "quality": {
            "discardedEventCount": len(events) - sum(chain["eventCount"] for chain in chains),
            "templateCount": len(templates),
            "minConfidence": min_confidence,
            "minSupport": min_support,
            "maxGapSeconds": max_gap_seconds,
        },
        "algorithm": {"id": "EVENT_CHAIN_BASELINE", "version": VERSION},
    }


def behavior_matrix(payload: dict[str, Any]) -> dict[str, Any]:
    events = payload.get("events", [])
    min_confidence = float(payload.get("minConfidence", 0.0))
    window_seconds = float(payload.get("windowSeconds", 24 * 3600))
    peak_window_seconds = float(payload.get("peakWindowSeconds", min(window_seconds, 3600)))
    by_subject: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        subject = event_subject(event)
        event_type_value = event_type(event)
        occurred_at = event_time(event)
        if subject and event_type_value and occurred_at and float(event.get("confidence", 1.0)) >= min_confidence:
            by_subject[subject].append(event)

    pair_occurrences: Counter[tuple[str, str]] = Counter()
    pair_subjects: dict[tuple[str, str], set[str]] = defaultdict(set)
    pair_deltas: dict[tuple[str, str], list[float]] = defaultdict(list)
    event_types: set[str] = set()
    for subject, subject_events in sorted(by_subject.items()):
        ordered = sorted(subject_events, key=lambda item: parse_time(event_time(item)))
        event_types.update(event_type(item) for item in ordered)
        for left_index, left in enumerate(ordered):
            for right in ordered[left_index + 1:]:
                delta = (parse_time(event_time(right))
                         - parse_time(event_time(left))).total_seconds()
                if delta > window_seconds:
                    break
                pair = (event_type(left), event_type(right))
                pair_occurrences[pair] += 1
                pair_subjects[pair].add(subject)
                pair_deltas[pair].append(delta)

    subject_count = max(1, len(by_subject))
    relations = []
    matrix = {left: {right: 0.0 for right in sorted(event_types)}
              for left in sorted(event_types)}
    for pair in sorted(pair_occurrences):
        deltas = pair_deltas[pair]
        support = len(pair_subjects[pair]) / subject_count
        peak_density = sum(delta <= peak_window_seconds for delta in deltas) / len(deltas)
        mean_delta = statistics.fmean(deltas)
        decay = math.exp(-mean_delta / max(1.0, window_seconds))
        temporal_strength = clamp(0.5 * peak_density + 0.5 * decay)
        risk_weight = clamp(support * temporal_strength)
        matrix[pair[0]][pair[1]] = round(risk_weight, 8)
        relations.append({
            "relationId": "BR-" + digest({"pair": pair, "window": window_seconds})[:24],
            "fromEvent": pair[0],
            "toEvent": pair[1],
            "occurrenceCount": pair_occurrences[pair],
            "subjectCount": len(pair_subjects[pair]),
            "support": round(support, 8),
            "meanDeltaSeconds": round(mean_delta, 6),
            "medianDeltaSeconds": round(statistics.median(deltas), 6),
            "stddevDeltaSeconds": round(population_stddev(deltas), 6),
            "peakWindowDensity": round(peak_density, 8),
            "temporalStrength": round(temporal_strength, 8),
            "riskWeight": round(risk_weight, 8),
        })
    return {
        "inputSnapshotSha256": digest(payload),
        "subjectCount": len(by_subject),
        "eventTypes": sorted(event_types),
        "matrix": matrix,
        "relations": relations,
        "parameters": {
            "windowSeconds": window_seconds,
            "peakWindowSeconds": peak_window_seconds,
            "minConfidence": min_confidence,
        },
        "algorithm": {"id": "BEHAVIOR_MATRIX_BASELINE", "version": VERSION},
    }


def _diffusion_paths(payload: dict[str, Any], excluded_node: str | None = None) -> list[dict[str, Any]]:
    initial = {
        str(node.get("id")): clamp(float(node.get("initialRisk", 0.0)))
        for node in payload.get("nodes", []) if str(node.get("id", "")).strip()
    }
    edges: dict[str, list[tuple[str, float, str]]] = defaultdict(list)
    for edge in payload.get("edges", []):
        source = str(edge.get("source", "")).strip()
        target = str(edge.get("target", "")).strip()
        if not source or not target or source == excluded_node or target == excluded_node:
            continue
        effective = clamp(
            float(edge.get("weight", 1.0))
            * float(edge.get("cooccurrence", 1.0))
            * float(edge.get("temporalStrength", 1.0))
        )
        edges[source].append((target, effective, str(edge.get("relationType", "RELATED"))))
    for values in edges.values():
        values.sort()

    damping = clamp(float(payload.get("damping", 0.85)))
    max_hops = max(1, min(int(payload.get("maxHops", 4)), 10))
    min_path_score = clamp(float(payload.get("minPathScore", 0.01)))
    paths = []
    frontier = [
        (source, [source], risk, [])
        for source, risk in sorted(initial.items()) if risk > 0 and source != excluded_node
    ]
    for _ in range(max_hops):
        next_frontier = []
        for source, path, score, relations in frontier:
            for target, weight, relation_type in edges.get(path[-1], []):
                if target in path:
                    continue
                propagated = score * weight * damping
                if propagated < min_path_score:
                    continue
                next_path = path + [target]
                next_relations = relations + [relation_type]
                paths.append({
                    "source": source,
                    "target": target,
                    "nodes": next_path,
                    "relations": next_relations,
                    "hopCount": len(next_path) - 1,
                    "score": round(propagated, 8),
                })
                next_frontier.append((source, next_path, propagated, next_relations))
        frontier = next_frontier
        if not frontier:
            break
    return paths


def risk_diffusion(payload: dict[str, Any]) -> dict[str, Any]:
    paths = _diffusion_paths(payload)
    max_paths = max(10, min(int(payload.get("maxResults", 200)), 1000))
    total_path_count = len(paths)
    paths = sorted(paths, key=lambda item: (-item["score"], item["nodes"]))[:max_paths]
    initial = {
        str(node.get("id")): clamp(float(node.get("initialRisk", 0.0)))
        for node in payload.get("nodes", []) if str(node.get("id", "")).strip()
    }
    incoming: dict[str, list[float]] = defaultdict(list)
    for path in paths:
        incoming[path["target"]].append(float(path["score"]))
    node_scores = []
    for node_id in sorted(initial):
        probabilities = [initial[node_id], *incoming.get(node_id, [])]
        combined = 1.0 - math.prod(1.0 - clamp(value) for value in probabilities)
        node_scores.append({
            "nodeId": node_id,
            "initialRisk": round(initial[node_id], 8),
            "propagatedRisk": round(combined, 8),
            "incomingPathCount": len(incoming.get(node_id, [])),
        })

    baseline_total = sum(item["propagatedRisk"] for item in node_scores)
    interventions = []
    for candidate in sorted(initial):
        remaining_paths = _diffusion_paths(payload, candidate)
        remaining_incoming: dict[str, list[float]] = defaultdict(list)
        for path in remaining_paths:
            remaining_incoming[path["target"]].append(float(path["score"]))
        total = 0.0
        for node_id, risk in initial.items():
            if node_id == candidate:
                continue
            probabilities = [risk, *remaining_incoming.get(node_id, [])]
            total += 1.0 - math.prod(1.0 - clamp(value) for value in probabilities)
        reduction = baseline_total - total
        interventions.append({
            "nodeId": candidate,
            "riskReduction": round(max(0.0, reduction), 8),
            "riskReductionRatio": round(max(0.0, reduction) / baseline_total, 8)
            if baseline_total else 0.0,
        })
    interventions.sort(key=lambda item: (-item["riskReduction"], item["nodeId"]))
    interventions = interventions[:max(10, min(int(payload.get("maxInterventions", 100)), 500))]

    threshold = float(payload.get("decisionThreshold", 0.5))
    evidence = []
    for item in node_scores:
        if item["propagatedRisk"] < threshold:
            continue
        node_paths = [path for path in paths if path["target"] == item["nodeId"]]
        evidence.append({
            "subjectId": item["nodeId"],
            "score": item["propagatedRisk"],
            "decision": "SUSPECTED",
            "reasonCodes": ["RISK_DIFFUSION", "HIGH_PROPAGATED_RISK"],
            "contributions": [
                {"feature": "initialRisk", "contribution": item["initialRisk"]},
                {"feature": "incomingPaths",
                 "contribution": round(item["propagatedRisk"] - item["initialRisk"], 8)},
            ],
            "pathEvidence": node_paths[:10],
        })
    return {
        "inputSnapshotSha256": digest(payload),
        "nodeScores": node_scores,
        "highRiskPaths": paths,
        "totalDetectedPathCount": total_path_count,
        "resultsTruncated": total_path_count > len(paths),
        "interventions": interventions,
        "evidence": evidence,
        "parameters": {
            "damping": clamp(float(payload.get("damping", 0.85))),
            "maxHops": max(1, min(int(payload.get("maxHops", 4)), 10)),
            "decisionThreshold": threshold,
        },
        "algorithm": {"id": "RISK_DIFFUSION_BASELINE", "version": VERSION},
    }


def incremental_learning(payload: dict[str, Any]) -> dict[str, Any]:
    templates = payload.get("existingTemplates", [])
    observations = payload.get("observedChains", [])
    threshold = clamp(float(payload.get("similarityThreshold", 0.8)))
    decay = clamp(float(payload.get("supportDecay", 1.0)))
    updates = []
    unmatched: Counter[tuple[str, ...]] = Counter()
    for observation in observations:
        sequence = [str(value) for value in observation.get("eventTypes", [])]
        matches = []
        for template in templates:
            template_sequence = [str(value) for value in template.get("eventTypes", [])]
            matches.append((sequence_similarity(sequence, template_sequence),
                            str(template.get("templateId", "")), template))
        best = max(matches, key=lambda item: (item[0], item[1]), default=(0.0, "", {}))
        if best[0] >= threshold:
            updates.append({
                "observationId": observation.get("chainId"),
                "matchedTemplateId": best[1],
                "similarity": best[0],
                "previousSupport": float(best[2].get("support", 0.0)),
                "updatedSupport": round(float(best[2].get("support", 0.0)) * decay + 1.0, 8),
            })
        else:
            unmatched[tuple(sequence)] += 1
    candidates = []
    for sequence, count in sorted(unmatched.items(), key=lambda item: (-item[1], item[0])):
        best_similarity = max(
            (sequence_similarity(list(sequence), [str(value) for value in template.get("eventTypes", [])])
             for template in templates), default=0.0)
        candidates.append({
            "candidateId": "ELC-" + digest({"sequence": sequence})[:24],
            "eventTypes": list(sequence),
            "occurrenceCount": count,
            "noveltyScore": round(1.0 - best_similarity, 8),
            "reviewStatus": "PENDING",
            "candidateSha256": digest({"sequence": sequence}),
        })
    return {
        "inputSnapshotSha256": digest(payload),
        "matchedCount": len(updates),
        "candidateCount": len(candidates),
        "templateUpdates": updates,
        "candidates": candidates,
        "parameters": {"similarityThreshold": threshold, "supportDecay": decay},
        "algorithm": {"id": "INCREMENTAL_CASE_BASELINE", "version": VERSION},
    }


def _graph_from_envelope(envelope: dict[str, Any]) -> tuple[dict[str, str], dict[str, list[dict[str, Any]]]]:
    node_types: dict[str, str] = {}
    for entity in envelope.get("entities", []):
        node_id = str(entity.get("entityUid") or entity.get("id") or "").strip()
        if node_id:
            node_types[node_id] = str(entity.get("entityType") or entity.get("type") or "ENTITY")
    for event in envelope.get("events", []):
        node_id = str(event.get("eventId") or event.get("id") or "").strip()
        if node_id:
            node_types[node_id] = str(event.get("standardEventCode")
                                      or event.get("eventType") or "EVENT")
    adjacency: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for index, relationship in enumerate(envelope.get("relationships", [])):
        source = str(relationship.get("source") or "").strip()
        target = str(relationship.get("target") or "").strip()
        if not source or not target:
            continue
        relation_type = str(relationship.get("type") or "RELATED")
        weight = clamp(float(relationship.get(
            "riskWeight", relationship.get("weight", relationship.get("confidence", 0.5)))))
        edge = {
            "edgeId": str(relationship.get("relationshipId")
                          or relationship.get("id")
                          or f"REL-{index}"),
            "source": source,
            "target": target,
            "relationType": relation_type,
            "riskWeight": weight,
        }
        adjacency[source].append(edge)
        if not bool(relationship.get("directed", True)):
            adjacency[target].append({**edge, "source": target, "target": source})
        node_types.setdefault(source, "ENTITY")
        node_types.setdefault(target, "ENTITY")
    for edges in adjacency.values():
        edges.sort(key=lambda item: (item["target"], item["relationType"], item["edgeId"]))
    return node_types, adjacency


def detect_meta_paths(payload: dict[str, Any]) -> dict[str, Any]:
    envelope = payload.get("caseGraphEnvelope", {})
    node_types, adjacency = _graph_from_envelope(envelope)
    min_hops = max(1, int(payload.get("minHops", 2)))
    max_hops = min(8, max(min_hops, int(payload.get("maxHops", 5))))
    threshold = clamp(float(payload.get("deviationThreshold", 0.5)))
    templates = payload.get("compliantTemplates", [])
    sources = [str(value) for value in payload.get("sourceNodeIds", []) if str(value) in node_types]
    if not sources:
        sources = sorted(node_types)

    paths: list[dict[str, Any]] = []
    frontier = [(source, [source], [], []) for source in sorted(set(sources))]
    for _ in range(max_hops):
        next_frontier = []
        for source, nodes, relation_types, edges in frontier:
            for edge in adjacency.get(nodes[-1], []):
                target = edge["target"]
                if target in nodes:
                    continue
                next_nodes = nodes + [target]
                next_relations = relation_types + [edge["relationType"]]
                next_edges = edges + [edge]
                if len(next_relations) >= min_hops:
                    node_signature = [node_types.get(node, "ENTITY") for node in next_nodes]
                    similarities = []
                    for template in templates:
                        relation_similarity = sequence_similarity(
                            next_relations, [str(value) for value in template.get("relationTypes", [])])
                        expected_nodes = [str(value) for value in template.get("nodeTypes", [])]
                        node_similarity = sequence_similarity(node_signature, expected_nodes) \
                            if expected_nodes else relation_similarity
                        similarities.append((relation_similarity + node_similarity) / 2)
                    similarity = max(similarities, default=0.0)
                    deviation = 1.0 - similarity if templates else 1.0
                    structural_risk = statistics.fmean(
                        float(item["riskWeight"]) for item in next_edges)
                    score = clamp(0.65 * deviation + 0.35 * structural_risk)
                    if deviation >= threshold:
                        paths.append({
                            "pathId": "MP-" + digest({
                                "nodes": next_nodes, "relations": next_relations})[:24],
                            "source": source,
                            "target": target,
                            "nodes": next_nodes,
                            "nodeTypes": node_signature,
                            "relations": next_relations,
                            "edgeRefs": [item["edgeId"] for item in next_edges],
                            "hopCount": len(next_relations),
                            "baselineSimilarity": round(similarity, 8),
                            "deviationScore": round(deviation, 8),
                            "structuralRisk": round(structural_risk, 8),
                            "score": round(score, 8),
                        })
                next_frontier.append((source, next_nodes, next_relations, next_edges))
        frontier = next_frontier
        if not frontier:
            break

    paths.sort(key=lambda item: (-item["score"], item["nodes"], item["relations"]))
    total_path_count = len(paths)
    max_results = max(10, min(int(payload.get("maxResults", 200)), 1000))
    paths = paths[:max_results]
    occurrence: Counter[str] = Counter()
    weighted: dict[str, float] = defaultdict(float)
    for path in paths:
        for index, node in enumerate(path["nodes"]):
            position_weight = 2 if 0 < index < len(path["nodes"]) - 1 else 1
            occurrence[node] += position_weight
            weighted[node] += float(path["score"]) * position_weight
    maximum = max(occurrence.values(), default=1)
    hub_scores = [{
        "nodeId": node,
        "pathCount": occurrence[node],
        "hubCentrality": round(occurrence[node] / maximum, 8),
        "weightedRisk": round(weighted[node] / occurrence[node], 8),
    } for node in sorted(occurrence)]
    hub_scores.sort(key=lambda item: (-item["hubCentrality"], -item["weightedRisk"], item["nodeId"]))
    max_hubs = max(10, min(int(payload.get("maxHubs", 100)), 500))
    hub_scores = hub_scores[:max_hubs]
    evidence = []
    for hub in hub_scores:
        related = [path for path in paths if hub["nodeId"] in path["nodes"]]
        score = clamp(0.5 * hub["hubCentrality"] + 0.5 * hub["weightedRisk"])
        if score < threshold:
            continue
        evidence.append({
            "subjectId": hub["nodeId"],
            "score": round(score, 8),
            "decision": "SUSPECTED",
            "reasonCodes": ["META_PATH_DEVIATION", "HIGH_RISK_HUB"],
            "contributions": [
                {"feature": "hubCentrality", "contribution": round(0.5 * hub["hubCentrality"], 8)},
                {"feature": "pathRisk", "contribution": round(0.5 * hub["weightedRisk"], 8)},
            ],
            "pathEvidence": related[:10],
        })
    return {
        "inputSnapshotSha256": digest(payload),
        "detectedPaths": paths,
        "totalDetectedPathCount": total_path_count,
        "resultsTruncated": total_path_count > len(paths),
        "hubScores": hub_scores,
        "evidence": evidence,
        "parameters": {"minHops": min_hops, "maxHops": max_hops,
                       "deviationThreshold": threshold},
        "algorithm": {"id": "META_PATH_DEVIATION_BASELINE", "version": VERSION},
    }


def _event_amount(event: dict[str, Any]) -> float:
    attributes = event.get("attributes", {})
    value = attributes.get("amount", event.get("amount", 0.0)) if isinstance(attributes, dict) \
        else event.get("amount", 0.0)
    return float(value or 0.0)


def detect_temporal_anomalies(payload: dict[str, Any]) -> dict[str, Any]:
    events = [event for event in payload.get("events", [])
              if event_subject(event) and event_time(event)]
    window_seconds = max(1.0, float(payload.get("windowSeconds", 3600)))
    burst_min = max(2, int(payload.get("burstMinEvents", 3)))
    threshold = clamp(float(payload.get("decisionThreshold", 0.55)))
    night_start = int(payload.get("nightStartHour", 0))
    night_end = int(payload.get("nightEndHour", 5))
    by_subject: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        by_subject[event_subject(event)].append(event)
    all_amounts = [_event_amount(event) for event in events]
    median_amount = statistics.median(all_amounts) if all_amounts else 0.0
    deviations = [abs(value - median_amount) for value in all_amounts]
    mad = statistics.median(deviations) if deviations else 0.0

    anomalies = []
    subject_scores: dict[str, list[float]] = defaultdict(list)
    for subject, subject_events in sorted(by_subject.items()):
        ordered = sorted(subject_events, key=lambda item: (parse_time(event_time(item)),
                                                            str(item.get("eventId", ""))))
        times = [parse_time(event_time(event)) for event in ordered]
        for index, event in enumerate(ordered):
            amount = _event_amount(event)
            robust_z = abs(amount - median_amount) / (1.4826 * mad) if mad else 0.0
            amount_risk = clamp(robust_z / max(1.0, float(payload.get("robustZThreshold", 3.5))))
            hour = times[index].hour
            night_risk = 1.0 if (night_start <= hour <= night_end) else 0.0
            left = index
            while left > 0 and (times[index] - times[left - 1]).total_seconds() <= window_seconds:
                left -= 1
            burst_count = index - left + 1
            burst_risk = clamp(burst_count / burst_min)
            score = clamp(0.5 * amount_risk + 0.2 * night_risk + 0.3 * burst_risk)
            subject_scores[subject].append(score)
            if score < threshold:
                continue
            reasons = []
            if amount_risk >= 0.5: reasons.append("AMOUNT_OUTLIER")
            if night_risk: reasons.append("NIGHT_ACTIVITY")
            if burst_risk >= 1.0: reasons.append("BURST_ACTIVITY")
            anomalies.append({
                "anomalyId": "TA-" + digest({
                    "subject": subject, "event": event.get("eventId"), "window": window_seconds})[:24],
                "subjectId": subject,
                "eventId": event.get("eventId"),
                "occurredAt": event_time(event),
                "score": round(score, 8),
                "amount": amount,
                "robustZ": round(robust_z, 8),
                "burstCount": burst_count,
                "reasonCodes": reasons,
            })
    summaries = []
    evidence = []
    for subject in sorted(subject_scores):
        values = subject_scores[subject]
        score = 1.0 - math.prod(1.0 - clamp(value) for value in values)
        subject_anomalies = [item for item in anomalies if item["subjectId"] == subject]
        summaries.append({"subjectId": subject, "eventCount": len(values),
                          "anomalyCount": len(subject_anomalies), "score": round(score, 8)})
        if subject_anomalies:
            evidence.append({
                "subjectId": subject,
                "score": round(score, 8),
                "decision": "SUSPECTED",
                "reasonCodes": sorted(set(
                    reason for item in subject_anomalies for reason in item["reasonCodes"])),
                "contributions": [
                    {"feature": "maximumEventAnomaly",
                     "contribution": round(max(item["score"] for item in subject_anomalies), 8)},
                    {"feature": "anomalyDensity",
                     "contribution": round(len(subject_anomalies) / len(values), 8)},
                ],
                "pathEvidence": subject_anomalies[:10],
            })
    return {
        "inputSnapshotSha256": digest(payload),
        "anomalies": sorted(anomalies, key=lambda item: (-item["score"], item["anomalyId"])),
        "subjectScores": summaries,
        "evidence": evidence,
        "baseline": {"medianAmount": median_amount, "medianAbsoluteDeviation": mad},
        "parameters": {"windowSeconds": window_seconds, "burstMinEvents": burst_min,
                       "decisionThreshold": threshold},
        "algorithm": {"id": "MULTI_SCALE_TEMPORAL_BASELINE", "version": VERSION},
    }


def infer_local_hypergraph(payload: dict[str, Any]) -> dict[str, Any]:
    envelope = payload.get("caseGraphEnvelope", {})
    node_types, _ = _graph_from_envelope(envelope)
    threshold = clamp(float(payload.get("decisionThreshold", 0.5)))
    hyperedges = []
    incident: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for index, raw in enumerate(payload.get("hyperedges", [])):
        nodes = sorted(set(str(value) for value in raw.get("nodeIds", []) if str(value)))
        if len(nodes) < 2:
            continue
        base = clamp(float(raw.get("baseRisk", 0.5)))
        similarity = clamp(float(raw.get("featureSimilarity", 1.0)))
        temporal = clamp(float(raw.get("temporalStrength", 1.0)))
        score = clamp(base * (0.5 + 0.5 * similarity) * temporal)
        item = {
            "hyperedgeId": str(raw.get("hyperedgeId") or
                               "HE-" + digest({"nodes": nodes, "index": index})[:24]),
            "type": str(raw.get("type") or "LOCAL_RISK_GROUP"),
            "nodeIds": nodes,
            "baseRisk": base,
            "featureSimilarity": similarity,
            "temporalStrength": temporal,
            "score": round(score, 8),
        }
        hyperedges.append(item)
        for node in nodes:
            node_types.setdefault(node, "ENTITY")
            incident[node].append(item)
    hyperedges.sort(key=lambda item: (-item["score"], item["hyperedgeId"]))
    node_scores = []
    for node in sorted(node_types):
        edges = incident.get(node, [])
        score = 1.0 - math.prod(1.0 - float(edge["score"]) for edge in edges)
        node_scores.append({
            "nodeId": node, "nodeType": node_types[node],
            "incidentHyperedgeCount": len(edges), "riskScore": round(score, 8),
        })
    node_scores.sort(key=lambda item: (-item["riskScore"], item["nodeId"]))
    baseline = sum(item["riskScore"] for item in node_scores)
    interventions = []
    for edge in hyperedges:
        remaining = 0.0
        for node in node_types:
            scores = [float(item["score"]) for item in incident.get(node, [])
                      if item["hyperedgeId"] != edge["hyperedgeId"]]
            remaining += 1.0 - math.prod(1.0 - value for value in scores)
        reduction = max(0.0, baseline - remaining)
        interventions.append({
            "hyperedgeId": edge["hyperedgeId"],
            "riskReduction": round(reduction, 8),
            "riskReductionRatio": round(reduction / baseline, 8) if baseline else 0.0,
        })
    interventions.sort(key=lambda item: (-item["riskReduction"], item["hyperedgeId"]))
    evidence = []
    for item in node_scores:
        if item["riskScore"] < threshold:
            continue
        edges = incident[item["nodeId"]]
        evidence.append({
            "subjectId": item["nodeId"],
            "score": item["riskScore"],
            "decision": "SUSPECTED",
            "reasonCodes": ["LOCAL_HYPERGRAPH_RISK", "MULTI_RELATION_AGGREGATION"],
            "contributions": [{
                "feature": edge["type"], "contribution": edge["score"],
                "hyperedgeId": edge["hyperedgeId"]} for edge in edges],
            "pathEvidence": edges,
        })
    return {
        "inputSnapshotSha256": digest(payload),
        "hyperedgeScores": hyperedges,
        "nodeScores": node_scores,
        "interventions": interventions,
        "evidence": evidence,
        "parameters": {"decisionThreshold": threshold},
        "algorithm": {"id": "LOCAL_HYPERGRAPH_BASELINE", "version": VERSION},
    }


def cascade_inference(payload: dict[str, Any]) -> dict[str, Any]:
    upstream = payload.get("upstreamResults", [])
    threshold = clamp(float(payload.get("decisionThreshold", 0.65)))
    min_modules = max(1, int(payload.get("minModules", 2)))
    configured = payload.get("moduleWeights", {})
    by_subject: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for run in upstream:
        run_type = str(run.get("runType", "UNKNOWN"))
        weight = max(0.0, float(configured.get(run_type, 1.0)))
        result = run.get("result", {})
        for evidence in result.get("evidence", []):
            subject = str(evidence.get("subjectId", "")).strip()
            if subject:
                by_subject[subject].append({
                    "runId": run.get("runId"), "runType": run_type, "weight": weight,
                    "score": clamp(float(evidence.get("score", 0.0))),
                    "decision": evidence.get("decision"),
                    "reasonCodes": evidence.get("reasonCodes", []),
                    "pathEvidence": evidence.get("pathEvidence", []),
                })
    decisions = []
    evidence = []
    candidates = []
    for subject in sorted(by_subject):
        modules = by_subject[subject]
        unique_types = sorted(set(item["runType"] for item in modules))
        weight_total = sum(item["weight"] for item in modules)
        weighted_score = sum(item["score"] * item["weight"] for item in modules) / weight_total \
            if weight_total else 0.0
        confirmation_bonus = min(0.15, 0.05 * max(0, len(unique_types) - 1))
        score = clamp(weighted_score + confirmation_bonus)
        decision = "SUSPECTED" if score >= threshold and len(unique_types) >= min_modules else "REVIEW"
        item = {
            "decisionId": "CD-" + digest({
                "subject": subject, "runs": sorted(str(value.get("runId")) for value in modules)})[:24],
            "subjectId": subject,
            "score": round(score, 8),
            "decision": decision,
            "confirmingModules": unique_types,
            "moduleCount": len(unique_types),
        }
        decisions.append(item)
        evidence.append({
            "subjectId": subject,
            "score": round(score, 8),
            "decision": decision,
            "reasonCodes": ["CASCADE_CONFIRMED", *unique_types] if decision == "SUSPECTED"
                           else ["INSUFFICIENT_CASCADE_CONFIRMATION"],
            "contributions": [{
                "feature": module["runType"], "contribution": round(
                    module["score"] * module["weight"] / weight_total, 8) if weight_total else 0.0,
                "sourceRunId": module["runId"],
            } for module in modules] + ([{
                "feature": "confirmationBonus", "contribution": confirmation_bonus,
            }] if confirmation_bonus else []),
            "pathEvidence": [{
                "sourceRunId": module["runId"], "runType": module["runType"],
                "evidence": module["pathEvidence"][:5],
            } for module in modules],
        })
        if decision == "SUSPECTED":
            candidates.append({
                "candidateId": "RKC-" + digest(item)[:24],
                "knowledgeCode": "LOCAL-CASCADE-" + digest({
                    "subject": subject, "modules": unique_types})[:16].upper(),
                "knowledgeType": "LOCAL_RISK_PATTERN",
                "title": f"Local cascade risk for {subject}",
                "subjectId": subject,
                "score": round(score, 8),
                "confirmingModules": unique_types,
                "sourceRunIds": sorted(set(str(value.get("runId")) for value in modules)),
                "reviewStatus": "PENDING",
            })
    return {
        "inputSnapshotSha256": digest(payload),
        "decisions": decisions,
        "knowledgeCandidates": candidates,
        "evidence": evidence,
        "parameters": {"decisionThreshold": threshold, "minModules": min_modules},
        "algorithm": {"id": "LOCAL_CASCADE_FUSION_BASELINE", "version": VERSION},
    }


TEXT_TECHNIQUES = {
    "T0038": "代理安排",
    "T0023.001": "伪造KYC",
    "T0015.001": "VPN",
    "T0149": "知识分隔",
    "T0016.001": "微结构化",
    "T0119": "国内大宗现金配送",
    "T0055.001": "黄金转换",
    "T0055": "贵金属与宝石交易",
    "T0063": "加密货币ATM",
    "T0067.004": "DeFi交易",
    "T0002": "中介促进转让",
    "T0128": "加密货币投资",
    "T0098": "贷款方案",
    "T0031": "虚构销售",
    "T0035": "测试支付探测",
}

TEXT_INDICATOR_RULES = [
    {
        "code": "OBS_HIGH_FREQUENCY_ACTIVITY",
        "name": "高频密集交易",
        "terms": ["交易频繁", "频繁发生", "密集交易", "日均交易笔数", "累计交易量巨大",
                  "交易频率明显上升", "交易笔数较多", "持续活跃", "高频", "集中收取多笔",
                  "多笔资金"],
        "patterns": [r"(?:交易|发生交易|收款|付款)\s*\d+\s*笔"],
        "eventTerms": ["交易", "收款", "付款", "支付"],
    },
    {
        "code": "OBS_RAPID_PASS_THROUGH",
        "name": "资金快进快出",
        "terms": ["快进快出", "不留余额", "过渡性质", "迅速转出", "快速转出", "随即转出",
                  "短暂停留", "中转、过渡", "过渡功能", "余额长期维持在较低水平",
                  "快速跨境外流", "短期分散入账", "分拆转出", "分散转出"],
        "patterns": [
            r"(?:到账|入账)后.{0,20}(?:小时|分钟)内.{0,20}(?:分拆|分散|迅速|快速)?.{0,8}转出",
            r"资金到账后.{0,32}转出",
        ],
        "eventTerms": ["收款", "付款", "转账", "支付"],
    },
    {
        "code": "OBS_ROUND_AMOUNT_PATTERN",
        "name": "整数倍金额交易",
        "terms": ["整数倍", "金额较小", "小额交易"],
        "patterns": [r"(?:100|500|1000|2000|5000)元(?:及其)?整数倍"],
        "eventTerms": ["交易", "收款", "付款", "支付"],
    },
    {
        "code": "OBS_NIGHT_ACTIVITY",
        "name": "夜间集中交易",
        "terms": ["夜间交易", "凌晨交易", "夜间集中", "赌博交易活跃时间"],
        "patterns": [r"2[12]:\d{2}\s*[-—至]\s*(?:次日)?0?[0-6]:\d{2}"],
        "eventTerms": ["交易", "收款", "付款", "支付"],
    },
    {
        "code": "OBS_MULTI_ACCOUNT_LAYERING",
        "name": "多账户分层转移",
        "terms": ["多层账户", "层层吸纳", "逐级转", "多个个人账户", "多个账户",
                  "多账户", "多家对公账户", "资金转移网络", "关联账户"],
        "patterns": [],
        "eventTerms": ["收款", "付款", "转账"],
    },
    {
        "code": "OBS_CROSS_REGION_ACTIVITY",
        "name": "跨区域交易",
        "terms": ["跨区域", "开户地分散", "多个省份", "多省", "跨境电汇",
                  "跨境外流", "境外"],
        "patterns": [],
        "eventTerms": ["交易", "收款", "付款"],
    },
    {
        "code": "OBS_PROFILE_TRANSACTION_MISMATCH",
        "name": "客户背景与交易不匹配",
        "terms": ["身份与实际生产交易规模不符", "职业及收入水平明显不符", "个人背景存在异常",
                  "与个人背景不符", "与收入水平不符", "身份背景不匹配",
                  "与其身份背景不匹配", "正常个人账户特征差异较大", "与其职业和历史流水明显不符"],
        "patterns": [
            r"交易行为与.{0,24}(?:职业|收入|经营|历史流水).{0,20}(?:不符|不匹配)",
        ],
        "eventTerms": ["开户", "交易"],
    },
    {
        "code": "OBS_THIRD_PARTY_PAYMENT",
        "name": "第三方支付通道交易",
        "terms": ["第三方支付", "支付宝", "微信支付"],
        "patterns": [],
        "eventTerms": ["支付", "交易"],
    },
    {
        "code": "OBS_THRESHOLD_STRUCTURING",
        "name": "阈值下拆分存入",
        "terms": ["微结构化", "分笔低于", "低于5万元", "低于 5 万元", "接近阈值",
                  "拆分存入", "分拆存入"],
        "patterns": [r"分笔.{0,12}低于\s*\d+(?:\.\d+)?\s*万元"],
        "eventTerms": ["存入", "存款", "入账", "收款"],
    },
    {
        "code": "OBS_PRECIOUS_METAL_CONVERSION",
        "name": "贵金属资产转换",
        "terms": ["黄金转换", "金条", "贵金属交易", "黄金实物"],
        "patterns": [],
        "eventTerms": ["购买", "交易", "变现", "转换"],
    },
    {
        "code": "OBS_CRYPTO_LAYERING",
        "name": "加密资产跨链离析",
        "terms": ["加密ATM", "USDT", "DeFi", "跨链", "混币", "加密货币投资"],
        "patterns": [],
        "eventTerms": ["兑换", "交易", "转账", "转换"],
    },
    {
        "code": "OBS_DISGUISED_BUSINESS_RETURN",
        "name": "虚构业务名义回流",
        "terms": ["贷款方案", "经营贷款", "虚构销售", "技术服务费名义回流"],
        "patterns": [],
        "eventTerms": ["贷款", "销售", "回流", "收款"],
    },
]

TEXT_TECHNIQUE_RULES = [
    {
        "code": "T0016.001",
        "required": ["OBS_HIGH_FREQUENCY_ACTIVITY", "OBS_ROUND_AMOUNT_PATTERN"],
        "confidence": 0.74,
        "explanation": "材料同时记载高频交易与整数倍/小额金额特征，符合微结构化技术的候选适用条件。",
    },
    {
        "code": "T0002",
        "required": ["OBS_RAPID_PASS_THROUGH", "OBS_MULTI_ACCOUNT_LAYERING"],
        "confidence": 0.72,
        "explanation": "材料同时记载资金快进快出与多账户分层转移，符合中介促进转让技术的候选适用条件。",
    },
]


def _case_time(value: Any) -> datetime | None:
    text = str(value or "").strip()
    if not text:
        return None
    for candidate in (text, text.replace("/", "-").replace(" ", "T")):
        try:
            return parse_time(candidate)
        except (ValueError, TypeError):
            pass
    return None


def _stable_id(prefix: str, payload: Any) -> str:
    return f"{prefix}-{digest(payload)[:24].upper()}"


def _source_refs(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    result = []
    for item in items:
        result.append({
            "sourceRecordId": item.get("sourceRecordId"),
            "sourceLine": item.get("sourceLine"),
            "caseMember": bool(item.get("caseMember", True)),
        })
    return result


def _matter(case_id: str, matter_type: str, summary: str, business_value: str,
            certainty: str, event_refs: list[str], evidence_refs: list[Any],
            source_refs: list[Any], relation_type: str, details: dict[str, Any]) -> dict[str, Any]:
    identity = {
        "caseId": case_id, "matterType": matter_type, "summary": summary,
        "eventRefs": event_refs, "sourceRefs": source_refs,
    }
    return {
        "matterId": _stable_id("MATTER", identity),
        "matterType": matter_type,
        "summary": summary,
        "businessValue": business_value,
        "certainty": certainty,
        "eventRefs": event_refs,
        "evidenceRefs": evidence_refs,
        "sourceRefs": source_refs,
        "indicatorResultRefs": [],
        "techniqueCodes": [],
        "relationType": relation_type,
        "details": details,
    }


def _behavior_occurrence(case_id: str, pattern_code: str, pattern_class: str,
                         event_refs: list[str], fact_confidence: float,
                         pattern_confidence: float, evidence_strength: str,
                         matched_constraints: list[dict[str, Any]],
                         event_times: list[datetime]) -> dict[str, Any]:
    identity = {
        "caseId": case_id, "patternCode": pattern_code,
        "eventRefs": sorted(event_refs), "constraints": matched_constraints,
    }
    times = sorted(event_times)
    return {
        "occurrenceId": _stable_id("BPO", identity),
        "patternCode": pattern_code,
        "patternVersion": "1.0",
        "patternClass": pattern_class,
        "eventRefs": event_refs,
        "matchedConstraints": matched_constraints,
        "missingConstraints": [],
        "factConfidence": fact_confidence,
        "patternConfidence": pattern_confidence,
        "evidenceStrength": evidence_strength,
        "eventTimeStart": times[0].isoformat() if times else None,
        "eventTimeEnd": times[-1].isoformat() if times else None,
    }


def _risk_event(case_id: str, risk_type: str, title: str, summary: str,
                behavior_refs: list[str], event_refs: list[str],
                fact_confidence: float, risk_confidence: float,
                evidence_strength: str) -> dict[str, Any]:
    return {
        "riskEventId": _stable_id("RISK", {
            "caseId": case_id, "riskType": risk_type,
            "behaviorRefs": behavior_refs, "eventRefs": event_refs,
        }),
        "riskEventType": risk_type,
        "title": title,
        "summary": summary,
        "behaviorOccurrenceRefs": behavior_refs,
        "eventRefs": event_refs,
        "factConfidence": fact_confidence,
        "riskConfidence": risk_confidence,
        "evidenceStrength": evidence_strength,
        "reviewStatus": "PENDING",
        "status": "ACTIVE",
    }


def _investigation_hypothesis(case_id: str, risk_event_id: str, hypothesis: str,
                              evidence_needed: list[str],
                              recommended_actions: list[str]) -> dict[str, Any]:
    return {
        "hypothesisId": _stable_id("IH", {
            "caseId": case_id, "riskEventId": risk_event_id, "hypothesis": hypothesis,
        }),
        "riskEventId": risk_event_id,
        "hypothesis": hypothesis,
        "evidenceNeeded": evidence_needed,
        "recommendedActions": recommended_actions,
        "priority": "MEDIUM",
        "status": "OPEN",
        "blocking": False,
    }


def _explain_structured_case(payload: dict[str, Any]) -> dict[str, Any]:
    case_id = str(payload.get("caseId", "CASE"))
    transactions = [dict(item) for item in payload.get("transactions", [])
                    if isinstance(item, dict)]
    members = [item for item in transactions if item.get("caseMember", True)]
    contexts = [item for item in transactions if not item.get("caseMember", True)]
    events = [dict(item) for item in payload.get("events", []) if isinstance(item, dict)]
    event_ids = [str(item.get("eventId")) for item in events if item.get("eventId")]
    indicator_refs = [str(item.get("calculationId")) for item in payload.get("indicatorResults", [])
                      if isinstance(item, dict) and item.get("calculationId")]
    account_frequency: Counter[str] = Counter()
    for item in members:
        for key in ("sourceAccount", "targetAccount"):
            account = str(item.get(key, "")).strip()
            if account:
                account_frequency[account] += 1
    core_account = account_frequency.most_common(1)[0][0] if account_frequency else ""
    times = sorted(value for value in (_case_time(item.get("timestamp")) for item in members)
                   if value is not None)
    window_seconds = (times[-1] - times[0]).total_seconds() if len(times) > 1 else 0.0
    total_amount = sum(float(item.get("amount", 0) or 0) for item in members)
    currency = next((str(item.get("currency")) for item in members if item.get("currency")), "")
    source_refs = _source_refs(members)
    matters: list[dict[str, Any]] = []
    if len(members) >= 2 and core_account:
        matters.append(_matter(
            case_id, "SHORT_WINDOW_COMPOSITE",
            f"核心账户在 {int(window_seconds // 60)} 分钟窗口内发生 {len(members)} 笔资金动作，"
            f"合计 {total_amount:,.2f}{(' ' + currency) if currency else ''}",
            "把孤立交易信号组织为可调查的行为窗口，并说明信号归并依据。",
            "DERIVED", event_ids, [{"kind": "TRANSACTION_WINDOW",
                                    "transactionCount": len(members),
                                    "windowSeconds": window_seconds,
                                    "coreAccount": core_account}],
            source_refs, "SHARED_SUBJECT_AND_TIME_WINDOW",
            {"coreAccount": core_account, "transactionCount": len(members),
             "totalAmount": round(total_amount, 2), "currency": currency,
             "windowSeconds": window_seconds}))
    cross = [item for item in members
             if str(item.get("sourceAccount", "")) != str(item.get("targetAccount", ""))]
    if cross:
        amount = sum(float(item.get("amount", 0) or 0) for item in cross)
        counterparties = sorted({str(item.get("targetAccount")) for item in cross
                                 if item.get("targetAccount")})
        matters.append(_matter(
            case_id, "CROSS_SUBJECT_TRANSFER",
            f"核心账户发生 {len(cross)} 笔对外转账，涉及 {len(counterparties)} 个对手账户，"
            f"合计 {amount:,.2f}{(' ' + currency) if currency else ''}",
            "定位需要关注的交易对手和主体关系。",
            "OBSERVED", event_ids, [{"kind": "CROSS_ACCOUNT_TRANSFER",
                                     "transactionCount": len(cross)}],
            _source_refs(cross), "FUNDS_FLOW_TO",
            {"counterpartyAccounts": counterparties, "amount": round(amount, 2),
             "currency": currency}))
    self_actions = [item for item in members
                    if item.get("sourceAccount")
                    and item.get("sourceAccount") == item.get("targetAccount")]
    if self_actions:
        amount = sum(float(item.get("amount", 0) or 0) for item in self_actions)
        matters.append(_matter(
            case_id, "ASSET_RECONFIGURATION",
            f"核心账户发生 {len(self_actions)} 笔同账户资金动作，"
            f"合计 {amount:,.2f}{(' ' + currency) if currency else ''}",
            "提示调查人员区分对外支付与账户内投资、再配置等不同资金目的。",
            "OBSERVED", event_ids, [{"kind": "SELF_ACCOUNT_ACTION",
                                     "transactionCount": len(self_actions)}],
            _source_refs(self_actions), "SAME_SUBJECT",
            {"amount": round(amount, 2), "currency": currency,
             "formats": sorted({str(item.get("paymentFormat")) for item in self_actions
                                if item.get("paymentFormat")})}))

    behavior_occurrences: list[dict[str, Any]] = []
    risk_events: list[dict[str, Any]] = []
    alternatives: list[dict[str, Any]] = []
    investigation_hypotheses: list[dict[str, Any]] = []
    if len(members) >= 2 and core_account:
        behavior = _behavior_occurrence(
            case_id, "SHORT_WINDOW_COMPOSITE", "RISK", event_ids,
            0.99, 0.82, "E5",
            [{"constraint": "MINIMUM_EVENTS", "actual": len(members), "required": 2},
             {"constraint": "SHARED_CORE_ACCOUNT", "actual": core_account},
             {"constraint": "WINDOW_SECONDS", "actual": window_seconds, "maximum": 600}],
            [value for value in (_case_time(item.get("timestamp")) for item in members)
             if value is not None])
        behavior_occurrences.append(behavior)
        risk = _risk_event(
            case_id, "SHORT_WINDOW_FUNDS_MOVEMENT_RISK", "短窗复合资金行为风险",
            "多笔资金动作在共享核心账户和短时间窗口内聚合，值得调查其经济目的。",
            [behavior["occurrenceId"]], event_ids, 0.99, 0.58, "E5")
        risk_events.append(risk)
        alternatives.append({
            "explanationId": _stable_id("ALT", {
                "caseId": case_id, "type": "NORMAL_BUSINESS",
                "riskEventId": risk["riskEventId"],
            }),
            "alternativeType": "NORMAL_BUSINESS",
            "title": "正常投资或账户内资产配置",
            "summary": "同账户再投资和大额资金动作也可能具有真实投资、借贷或经营目的；当前字段不足以排除。",
            "targetRiskEventId": risk["riskEventId"],
            "behaviorOccurrenceRefs": [behavior["occurrenceId"]],
            "supportingEvidenceRefs": [],
            "contradictingEvidenceRefs": source_refs,
            "confidence": 0.35,
            "reviewStatus": "PENDING",
            "status": "ACTIVE",
        })
        investigation_hypotheses.append(_investigation_hypothesis(
            case_id, risk["riskEventId"],
            "共享核心账户下的多笔资金动作是否具有统一、可核验的经济目的？",
            ["交易合同或产品信息", "账户主体关系", "交易备注或票据"],
            ["核对账户主体与受益所有人", "核对大额交易和再投资产品", "记录正常业务解释"]))

    behavior_refs = [item["occurrenceId"] for item in behavior_occurrences]
    techniques: list[dict[str, Any]] = []
    if cross:
        techniques.append({
            "occurrenceId": _stable_id("TO", {"caseId": case_id, "code": "T0002",
                                                "sources": _source_refs(cross)}),
            "techniqueCode": "T0002", "techniqueVersion": "1",
            "decision": "CANDIDATE", "status": "CANDIDATE",
            "eventRefs": event_ids, "behaviorOccurrenceRefs": behavior_refs,
            "sourceRefs": _source_refs(cross),
            "factConfidence": 0.99, "mappingConfidence": 0.62,
            "riskConfidence": 0.42, "evidenceStrength": "E5",
            "knowledgeAuthorityLevel": "K4",
            "explanation": "观察到跨主体资金转移，但现有数据不能确认对手主体充当中介。",
        })
    test_pairs = []
    for context in contexts:
        context_time = _case_time(context.get("timestamp"))
        context_amount = float(context.get("amount", 0) or 0)
        if not context_time or context_amount <= 0:
            continue
        for member in members:
            member_time = _case_time(member.get("timestamp"))
            member_amount = float(member.get("amount", 0) or 0)
            same_source = context.get("sourceAccount") == member.get("sourceAccount")
            gap = (member_time - context_time).total_seconds() if member_time else -1
            if same_source and 0 < gap <= 600 and member_amount >= context_amount * 100:
                test_pairs.append((context, member, gap))
    if test_pairs:
        context, member, gap = test_pairs[0]
        techniques.append({
            "occurrenceId": _stable_id("TO", {"caseId": case_id, "code": "T0035",
                                                "context": context.get("sourceRecordId"),
                                                "member": member.get("sourceRecordId")}),
            "techniqueCode": "T0035", "techniqueVersion": "1",
            "decision": "WEAK_CANDIDATE", "status": "CANDIDATE",
            "eventRefs": event_ids, "behaviorOccurrenceRefs": behavior_refs,
            "sourceRefs": _source_refs([context, member]),
            "factConfidence": 0.99, "mappingConfidence": 0.45,
            "riskConfidence": 0.25, "evidenceStrength": "E5",
            "knowledgeAuthorityLevel": "K4",
            "explanation": f"同一来源账户的小额动作在{int(gap)}秒后出现显著更大金额操作；"
                           "小额记录仅作为上下文，不能据此确认主观探测目的。",
        })
    for matter in matters:
        matter["techniqueCodes"] = [item["techniqueCode"] for item in techniques
                                    if set(item["eventRefs"]) & set(matter["eventRefs"])]
    suggestions = [
        {
            "suggestionId": _stable_id("SUG", {"caseId": case_id, "topic": "经济目的"}),
            "targetType": "CASE", "targetId": case_id,
            "topic": "建议关注大额交易或资产再配置的经济目的",
            "reason": "当前结构化字段能够说明金额、账户和时间，但不能直接说明行为目的。",
            "expectedMaterial": "如未来可得，可查看产品、合同、票据或交易备注。",
            "priority": "MEDIUM", "status": "OPEN", "blocking": False,
        }
    ]
    for matter in matters:
        matter["indicatorResultRefs"] = indicator_refs
    for behavior in behavior_occurrences:
        behavior["indicatorResultRefs"] = indicator_refs
    for technique in techniques:
        technique["indicatorResultRefs"] = indicator_refs
    return {
        "inputSnapshotSha256": digest(payload),
        "caseId": case_id,
        "sourceType": "STRUCTURED",
        "behaviorPatternOccurrences": behavior_occurrences,
        "riskEvents": risk_events,
        "alternativeExplanations": alternatives,
        "investigationHypotheses": investigation_hypotheses,
        "matters": matters,
        "techniqueOccurrences": techniques,
        "techniqueAssessments": [{"techniqueCode": "T0016.001",
                                  "decision": "NOT_APPLICABLE",
                                  "reason": "案例成员交易为大额动作，未观察到阈值下多笔拆分。"}],
        "reviewSuggestions": suggestions,
        "algorithm": {"id": "AMLTRIX_CASE_MATTER_BASELINE", "version": VERSION},
    }


def _text_evidence(description: str, terms: list[str]) -> list[dict[str, Any]]:
    matches = []
    for term in terms:
        index = description.find(term)
        if index >= 0:
            start = max(0, index - 24)
            end = min(len(description), index + len(term) + 40)
            matches.append({"kind": "TEXT_SPAN", "term": term, "start": start,
                            "end": end, "quote": description[start:end]})
    return matches


TEXT_FACT_RULES = [
    {
        "matterType": "ORGANIZED_ROLE_ARRANGEMENT",
        "termFacts": {
            "组织": "材料记载存在人员组织与分工安排",
            "招募": "材料记载存在人员招募行为",
            "车手": "材料记载存在车手参与资金操作",
            "代理安排": "材料记载存在代理人员安排",
            "伪造KYC": "材料记载存在伪造客户身份识别资料",
        },
        "eventTerms": ["组织", "招募", "车手", "代理", "身份"],
        "businessValue": "定位组织者、执行者和不同操作环节责任人。",
    },
    {
        "matterType": "THRESHOLD_AMOUNT_ACTIVITY",
        "termFacts": {
            "低于5万元": "材料记载存在低于5万元的资金操作",
        },
        "eventTerms": ["低于5万元", "分笔", "THRESHOLD"],
        "businessValue": "核查金额分布、交易方向和是否存在规避报告阈值的主观目的。",
    },
    {
        "matterType": "CASH_DEPOSIT",
        "termFacts": {
            "现金存入": "材料记载现金被存入金融账户",
            "存现": "材料记载存在现金存款行为",
            "现金存款": "材料记载存在现金存款行为",
            "存入银行账户": "材料记载资金被存入银行账户",
        },
        "eventTerms": ["现金存入", "存现", "现金存款", "CASH_DEPOSIT"],
        "businessValue": "核查现金来源、存款人、账户实际控制人和存入目的。",
    },
    {
        "matterType": "ACCOUNT_MULE_USAGE",
        "termFacts": {
            "卡农": "材料记载使用卡农参与资金流转",
            "收集银行卡": "材料记载收集他人银行卡参与资金流转",
            "使用他人银行账户": "材料记载使用他人银行账户参与资金流转",
            "寻找银行卡": "材料记载寻找他人银行卡参与资金流转",
            "提供银行卡": "材料记载有人提供银行卡参与资金流转",
            "出借银行卡": "材料记载有人出借银行卡参与资金流转",
        },
        "eventTerms": ["卡农", "银行卡", "银行账户", "ACCOUNT_MULE"],
        "businessValue": "核查银行卡持有人、实际控制人、使用人和资金受益人。",
    },
    {
        "matterType": "CASH_WITHDRAWAL",
        "termFacts": {
            "取现": "涉案资金进入银行账户后被取现",
        },
        "eventTerms": ["取现", "CASH_WITHDRAWAL"],
        "businessValue": "核查取现人、取现凭证、现金交付对象及取现后的资金去向。",
    },
    {
        "matterType": "CASH_DELIVERY",
        "termFacts": {
            "现金配送": "材料记载存在现金配送行为",
        },
        "eventTerms": ["现金配送", "现金交付", "CASH_DELIVERY"],
        "businessValue": "核查现金来源、配送人员、交付地点和最终接收人。",
    },
    {
        "matterType": "ONWARD_FUNDS_TRANSFER",
        "termFacts": {
            "取现后": "材料记载取现后资金继续转移",
            "转移给": "材料记载资金继续转移给其他人员",
            "转至": "材料记载资金继续转至其他账户或支付渠道",
            "指定收款账户": "材料记载资金转至指定收款账户",
            "收款码账户": "材料记载资金转至收款码账户",
        },
        "eventTerms": ["转账", "转移", "指定收款", "收款码", "FUNDS_TRANSFER"],
        "businessValue": "沿取现、支付渠道和指定收款方还原后续资金链路。",
    },
    {
        "matterType": "PRECIOUS_METAL_CONVERSION",
        "termFacts": {
            "金条": "材料记载资金被转换为金条",
            "黄金": "材料记载资金被转换为黄金资产",
        },
        "eventTerms": ["金条", "黄金", "PRECIOUS_METAL"],
        "businessValue": "核查贵金属购买、交付、保管和变现记录。",
    },
    {
        "matterType": "DIGITAL_ASSET_CONVERSION",
        "termFacts": {
            "USDT": "材料记载资金被兑换为USDT",
        },
        "eventTerms": ["USDT", "CRYPTO", "DIGITAL_ASSET"],
        "businessValue": "核查法币入口、交易平台、钱包地址和实际控制人。",
    },
    {
        "matterType": "DEFI_ACTIVITY",
        "termFacts": {
            "DeFi": "材料记载资金进入DeFi协议",
        },
        "eventTerms": ["DeFi", "DEFI"],
        "businessValue": "核查协议、合约地址、钱包地址和链上交易。",
    },
    {
        "matterType": "CROSS_CHAIN_TRANSFER",
        "termFacts": {
            "跨链": "材料记载加密资产发生跨链转移",
        },
        "eventTerms": ["跨链", "CROSS_CHAIN"],
        "businessValue": "核查源链、目标链、跨链协议和关联钱包。",
    },
    {
        "matterType": "MULTI_ACCOUNT_TRANSFER",
        "termFacts": {
            "多层账户": "材料记载资金经多层账户转移",
        },
        "eventTerms": ["多层账户", "逐级转移", "FUNDS_TRANSFER"],
        "businessValue": "还原账户层级、实际控制关系和最终资金去向。",
    },
    {
        "matterType": "LOAN_JUSTIFICATION",
        "termFacts": {
            "贷款方案": "材料记载使用贷款方案解释资金安排",
            "经营贷款": "材料记载资金涉及经营贷款安排",
        },
        "eventTerms": ["贷款", "LOAN"],
        "businessValue": "核对贷款主体、合同、还款来源和资金真实用途。",
    },
    {
        "matterType": "FICTITIOUS_TRADE",
        "termFacts": {
            "虚构销售": "材料记载存在虚构销售行为",
        },
        "eventTerms": ["虚构销售", "销售", "SALE"],
        "businessValue": "核对交易合同、货物流、发票流和资金流是否一致。",
    },
    {
        "matterType": "SERVICE_FEE_JUSTIFICATION",
        "termFacts": {
            "服务费": "材料记载资金以服务费名义流转",
        },
        "eventTerms": ["服务费", "SERVICE_FEE"],
        "businessValue": "核对服务内容、合同履行和费用定价是否真实合理。",
    },
    {
        "matterType": "LEGITIMATE_ASSET_INVESTMENT",
        "termFacts": {
            "购买房产": "材料记载资金被用于购买房产",
            "投资企业": "材料记载资金被用于企业投资",
            "购买证券": "材料记载资金被用于购买证券",
            "合法经营收入": "材料将相关资金解释为合法经营收入",
        },
        "eventTerms": ["购买房产", "投资企业", "购买证券", "经营收入",
                       "REAL_ESTATE", "SECURITY_INVESTMENT"],
        "businessValue": "核查资产购买、投资来源及所谓合法收入的真实性。",
    },
    {
        "matterType": "OPERATIONAL_EVASION",
        "termFacts": {
            "VPN": "材料记载使用VPN隐藏通信或操作来源",
            "知识分隔": "材料记载相关人员之间存在知识分隔",
            "测试支付": "材料记载存在测试支付行为",
            "小额测试": "材料记载存在小额测试支付行为",
        },
        "eventTerms": ["VPN", "知识分隔", "测试支付", "小额测试", "TEST_PAYMENT"],
        "businessValue": "从设备、通信及先导支付记录核查跨阶段关联。",
    },
]


def _compose_fact_summary(evidence: list[dict[str, Any]],
                          term_facts: dict[str, str],
                          matter_type: str) -> str:
    matched_terms = [str(item.get("term") or "") for item in evidence]
    if matter_type == "ACCOUNT_MULE_USAGE":
        return "涉案资金流转涉及他人名下银行卡或银行账户"
    if matter_type == "ONWARD_FUNDS_TRANSFER":
        after_withdrawal = "取现后" in matched_terms
        destinations = []
        if "转移给" in matched_terms:
            destinations.append("其他人员")
        if "转至" in matched_terms:
            destinations.append("其他账户或支付渠道")
        if "指定收款账户" in matched_terms:
            destinations.append("指定收款账户")
        if "收款码账户" in matched_terms:
            destinations.append("收款码账户")
        destination = "、".join(dict.fromkeys(destinations))
        prefix = "涉案资金存在取现后继续转移的路径" if after_withdrawal else "涉案资金存在继续转移的路径"
        return prefix + (f"，流向包括{destination}" if destination else "")
    facts = []
    for item in evidence:
        fact = term_facts.get(str(item.get("term") or ""))
        if fact and fact not in facts:
            facts.append(fact)
    return "；".join(facts)


def _related_text_events(events: list[dict[str, Any]],
                         event_terms: list[str],
                         matter_type: str = "") -> list[str]:
    related = []
    for event in events:
        event_id = str(event.get("eventId") or "")
        if not event_id:
            continue
        searchable = " ".join(str(event.get(key) or "") for key in (
            "eventName", "eventType", "eventFrameCode", "eventStandardCode",
            "standardEventCode", "ruleName"))
        direct_match = any(
            term.lower() in searchable.lower() for term in event_terms
        )
        account_control_match = (
            matter_type == "ACCOUNT_MULE_USAGE"
            and (
                re.search(r"取现.+卡内资金", searchable) is not None
                or any(code in searchable for code in (
                    "ACCOUNT_MULE_USAGE", "ACCOUNT_CONTROL", "CARD_PROVISION"
                ))
            )
        )
        if direct_match or account_control_match:
            related.append(event_id)
    return list(dict.fromkeys(related))


def _text_semantic_observations(case_id: str, description: str,
                                 events: list[dict[str, Any]]) -> list[dict[str, Any]]:
    all_event_ids = [str(item.get("eventId")) for item in events if item.get("eventId")]
    observations = []
    for rule in TEXT_INDICATOR_RULES:
        evidence = _text_evidence(description, rule["terms"])
        seen = {(item["start"], item["end"], item["term"]) for item in evidence}
        for pattern in rule["patterns"]:
            for match in re.finditer(pattern, description):
                key = (match.start(), match.end(), match.group(0))
                if key in seen:
                    continue
                seen.add(key)
                start = max(0, match.start() - 24)
                end = min(len(description), match.end() + 40)
                evidence.append({
                    "kind": "TEXT_SPAN", "term": match.group(0),
                    "start": start, "end": end, "quote": description[start:end],
                })
        if not evidence:
            continue
        related_event_ids = []
        for event in events:
            event_id = str(event.get("eventId") or "")
            searchable = " ".join(str(event.get(key) or "") for key in (
                "eventName", "eventType", "eventFrameCode", "ruleName"))
            if event_id and any(term in searchable for term in rule["eventTerms"]):
                related_event_ids.append(event_id)
        related_event_ids = related_event_ids[:4] or all_event_ids[:3]
        value = len(evidence)
        observations.append({
            "observationId": _stable_id("OBS", {
                "caseId": case_id, "observationCode": rule["code"],
                "content": digest(description),
            }),
            "observationCode": rule["code"],
            "observationName": rule["name"],
            "occurrenceCount": value,
            "assertionMode": "REPORTED",
            "observabilityType": "SEMANTIC_OBSERVATION",
            "eventRefs": related_event_ids,
            "evidenceRefs": evidence,
            "explanation": f"来源材料命中{value}处“{rule['name']}”文本证据；"
                           "该结果表示材料陈述，仍需交易流水复核。",
        })
    return observations


def _explain_text_case(payload: dict[str, Any]) -> dict[str, Any]:
    case_id = str(payload.get("caseId", "CASE"))
    description = str(payload.get("description", ""))
    events = [dict(item) for item in payload.get("events", []) if isinstance(item, dict)]
    event_ids = [str(item.get("eventId")) for item in events if item.get("eventId")]
    indicator_observations = _text_semantic_observations(case_id, description, events)
    indicator_by_code = {
        item["observationCode"]: item for item in indicator_observations
    }
    explicit_codes = []
    for code in re.findall(r"T\d{4}(?:\.\d{3})?", description):
        if code in TEXT_TECHNIQUES and code not in explicit_codes:
            explicit_codes.append(code)
    matters = []
    for rule in TEXT_FACT_RULES:
        term_facts = rule["termFacts"]
        evidence = _text_evidence(description, list(term_facts))
        if not evidence:
            continue
        summary = _compose_fact_summary(
            evidence, term_facts, str(rule["matterType"]))
        if not summary:
            continue
        related_event_ids = _related_text_events(
            events, rule["eventTerms"], str(rule["matterType"]))
        basis_type = "MIXED_EVIDENCE" if related_event_ids else "TEXT_EVIDENCE"
        matters.append(_matter(
            case_id, rule["matterType"], summary, rule["businessValue"], "REPORTED",
            related_event_ids, evidence,
            [{"sourceType": "CASE_DESCRIPTION", "caseId": case_id,
              "contentSha256": digest(description)}],
            "TEXT_SEMANTIC_RELATION",
            {"productClass": "FACT_SUMMARY",
             "analysisRoute": "TRADITIONAL_FACT",
             "theoryRole": "OBSERVED_FACT",
             "factScope": "CASE_LEVEL_SUMMARY",
             "basisType": basis_type,
             "summaryMode": "EVIDENCE_COMPOSED",
             "matchedTerms": [item["term"] for item in evidence]}))
    indicator_matter_rules = [
        (
            "HIGH_FREQUENCY_PASS_THROUGH",
            ["OBS_HIGH_FREQUENCY_ACTIVITY", "OBS_RAPID_PASS_THROUGH"],
            "账户交易高频且资金快进快出，形成明显的过渡型资金活动。",
            "核查资金进入后停留时间、余额变化及后续收款账户。",
        ),
        (
            "ROUND_AMOUNT_STRUCTURING",
            ["OBS_HIGH_FREQUENCY_ACTIVITY", "OBS_ROUND_AMOUNT_PATTERN"],
            "大量高频交易反复出现整数倍或小额金额特征。",
            "核查金额分布、阈值附近交易和拆分交易的时间连续性。",
        ),
        (
            "MULTI_ACCOUNT_LAYERING",
            ["OBS_RAPID_PASS_THROUGH", "OBS_MULTI_ACCOUNT_LAYERING"],
            "多个关联账户之间存在逐级、快速转移资金行为。",
            "还原账户层级、资金去向和各账户实际控制关系。",
        ),
        (
            "CONTEXTUAL_RISK_MISMATCH",
            ["OBS_PROFILE_TRANSACTION_MISMATCH"],
            "客户职业、收入或经营背景与实际交易规模不匹配。",
            "复核客户尽调、经营资料及交易对手的真实业务关系。",
        ),
        (
            "THRESHOLD_STRUCTURING",
            ["OBS_THRESHOLD_STRUCTURING"],
            "资金以低于报告阈值的方式分笔存入，形成阈值下拆分行为。",
            "复核各笔存入的时间连续性、实际控制人及拆分目的。",
        ),
        (
            "HIGH_FREQUENCY_DIGITAL_PAYMENT",
            ["OBS_HIGH_FREQUENCY_ACTIVITY", "OBS_THIRD_PARTY_PAYMENT"],
            "账户高频交易主要通过第三方支付通道完成，形成高频数字支付活动。",
            "复核支付平台对手、设备和资金入出时间，识别账户的实际用途。",
        ),
        (
            "CROSS_BORDER_MULTI_ACCOUNT_TRANSFER",
            ["OBS_MULTI_ACCOUNT_LAYERING", "OBS_CROSS_REGION_ACTIVITY"],
            "资金经多个账户分散后继续跨境或跨区域转移。",
            "还原境内账户层级、跨境汇出路径及最终受益人。",
        ),
    ]
    for matter_type, required, summary, business_value in indicator_matter_rules:
        matched = [code for code in required if code in indicator_by_code]
        if len(matched) != len(required):
            continue
        evidence = [
            item for code in matched
            for item in indicator_by_code[code]["evidenceRefs"]
        ]
        related_event_ids = list(dict.fromkeys(
            event_id for code in matched
            for event_id in indicator_by_code[code]["eventRefs"]
        ))
        matter = _matter(
            case_id, matter_type, summary, business_value, "REPORTED",
            related_event_ids, evidence,
            [{"sourceType": "CASE_DESCRIPTION", "caseId": case_id,
              "contentSha256": digest(description)}],
            "TEXT_INDICATOR_PATTERN",
            {"observationCodes": matched,
             "matchedTerms": [item["term"] for item in evidence]})
        matter["observationCodes"] = matched
        matter["details"]["productClass"] = "FACT_SUMMARY"
        matter["details"]["analysisRoute"] = "TRADITIONAL_FACT"
        matter["details"]["theoryRole"] = "OBSERVED_FACT"
        matter["details"]["factScope"] = "CASE_LEVEL_SUMMARY"
        matter["details"]["basisType"] = (
            "MIXED_EVIDENCE" if related_event_ids else "TEXT_EVIDENCE"
        )
        matters.append(matter)
    # Worker只抽取语义观察，不再直接生成指标、技术或战术。
    # 正式指标由后端对指标管理中的 ACTIVE 定义进行语义匹配；
    # AMLTRIX 技术和战术再由知识关系反向解析。
    techniques = []
    behavior_occurrences: list[dict[str, Any]] = []
    risk_events: list[dict[str, Any]] = []
    alternatives: list[dict[str, Any]] = []
    investigation_hypotheses: list[dict[str, Any]] = []
    if indicator_observations:
        pattern_rules = [
            ("TEXT_HIGH_FREQUENCY_PASS_THROUGH",
             ["OBS_HIGH_FREQUENCY_ACTIVITY", "OBS_RAPID_PASS_THROUGH"]),
            ("TEXT_ROUND_AMOUNT_STRUCTURING",
             ["OBS_HIGH_FREQUENCY_ACTIVITY", "OBS_ROUND_AMOUNT_PATTERN"]),
            ("TEXT_MULTI_ACCOUNT_LAYERING",
             ["OBS_RAPID_PASS_THROUGH", "OBS_MULTI_ACCOUNT_LAYERING"]),
            ("TEXT_THRESHOLD_STRUCTURING",
             ["OBS_THRESHOLD_STRUCTURING"]),
            ("TEXT_HIGH_FREQUENCY_DIGITAL_PAYMENT",
             ["OBS_HIGH_FREQUENCY_ACTIVITY", "OBS_THIRD_PARTY_PAYMENT"]),
            ("TEXT_CROSS_BORDER_MULTI_ACCOUNT",
             ["OBS_MULTI_ACCOUNT_LAYERING", "OBS_CROSS_REGION_ACTIVITY"]),
        ]
        for pattern_code, required in pattern_rules:
            if not all(code in indicator_by_code for code in required):
                continue
            related_event_ids = list(dict.fromkeys(
                event_id for code in required
                for event_id in indicator_by_code[code]["eventRefs"]
            ))
            behavior = _behavior_occurrence(
                case_id, pattern_code, "RISK", related_event_ids,
                0.85, 0.76, "E2",
                [{"constraint": code, "actual": True, "required": True}
                 for code in required],
                [])
            behavior["observationCodes"] = required
            behavior_occurrences.append(behavior)
        if not behavior_occurrences:
            return {
                "inputSnapshotSha256": digest(payload),
                "caseId": case_id,
                "sourceType": "TEXT",
                "semanticObservations": indicator_observations,
                "behaviorPatternOccurrences": [],
                "riskEvents": [],
                "alternativeExplanations": [],
                "investigationHypotheses": [],
                "matters": matters,
                "techniqueOccurrences": [],
                "techniqueAssessments": [],
                "reviewSuggestions": [],
                "algorithm": {"id": "TRADITIONAL_TEXT_FACT_BASELINE", "version": VERSION},
            }
        behavior_ids = [item["occurrenceId"] for item in behavior_occurrences]
        risk_event_ids = list(dict.fromkeys(
            event_id for item in behavior_occurrences
            for event_id in item["eventRefs"]
        ))
        risk = _risk_event(
            case_id, "REPORTED_COMPOSITE_LAUNDERING_SCENARIO",
            "多维异常资金行为风险",
            "来源文本中的多项独立语义观察共同支持传统规则下的异常资金行为候选；"
            "该结论不等同于正式指标命中、AMLTRIX解释或流水核验。",
            behavior_ids, risk_event_ids, 0.85, 0.62, "E2")
        risk_events.append(risk)
        investigation_hypotheses.append(_investigation_hypothesis(
            case_id, risk["riskEventId"],
            "文本所述资金动作是否能由逐笔流水和渠道记录连接为连续链路？",
            ["账户流水", "材料明确涉及的支付渠道或资产交易记录"],
            ["按涉案主体和时间检索现有内部数据", "记录无法取得的外部材料"]))

    suggestions = [{
        "suggestionId": _stable_id("SUG", {"caseId": case_id, "topic": "资金连续性"}),
        "targetType": "CASE", "targetId": case_id,
        "topic": "建议核查材料所述资金动作的连续性",
        "reason": "当前材料能够提供角色、手法和路径语义，但不包含完整逐笔流水。",
        "expectedMaterial": "账户流水，以及材料明确涉及的支付渠道、现金或资产交易记录。",
        "priority": "MEDIUM", "status": "OPEN", "blocking": False,
    }]
    return {
        "inputSnapshotSha256": digest(payload),
        "caseId": case_id,
        "sourceType": "TEXT",
        "semanticObservations": indicator_observations,
        "behaviorPatternOccurrences": behavior_occurrences,
        "riskEvents": risk_events,
        "alternativeExplanations": alternatives,
        "investigationHypotheses": investigation_hypotheses,
        "matters": matters,
        "techniqueOccurrences": techniques,
        "techniqueAssessments": [],
        "reviewSuggestions": suggestions,
        "algorithm": {"id": "TRADITIONAL_TEXT_FACT_BASELINE", "version": VERSION},
    }


def explain_case(payload: dict[str, Any]) -> dict[str, Any]:
    source_type = str(payload.get("sourceType", "")).upper()
    if source_type == "STRUCTURED":
        return _explain_structured_case(payload)
    if source_type in {"TEXT", "UNSTRUCTURED"}:
        return _explain_text_case(payload)
    raise ValueError("sourceType must be STRUCTURED or TEXT")


def infer(payload: dict[str, Any]) -> dict[str, Any]:
    subjects = payload.get("subjects", [])
    weights = payload.get("weights", {})
    threshold = float(payload.get("threshold", 0.5))
    evidence = []
    for subject in subjects:
        features = subject.get("features", {})
        contributions = []
        score = 0.0
        total_weight = 0.0
        for name, raw_weight in sorted(weights.items()):
            weight = float(raw_weight)
            value = float(features.get(name, 0.0))
            contribution = value * weight
            score += contribution
            total_weight += abs(weight)
            contributions.append({
                "feature": name, "value": value, "weight": weight,
                "contribution": round(contribution, 8),
            })
        normalized = score / total_weight if total_weight else 0.0
        decision = "SUSPECTED" if normalized >= threshold else "NORMAL"
        reasons = [item["feature"] for item in contributions if item["contribution"] > 0]
        evidence.append({
            "subjectId": str(subject.get("subjectId", "")),
            "score": round(normalized, 8),
            "decision": decision,
            "reasonCodes": reasons,
            "contributions": contributions,
            "pathEvidence": subject.get("pathEvidence", []),
        })
    return {
        "inputSnapshotSha256": digest(payload),
        "model": {"id": payload.get("modelId", "WEIGHTED_RISK_BASELINE"),
                  "version": payload.get("modelVersion", VERSION)},
        "threshold": threshold,
        "evidence": evidence,
    }


def evaluate(payload: dict[str, Any]) -> dict[str, Any]:
    rows = payload.get("rows", [])
    tp = fp = tn = fn = 0
    for row in rows:
        actual = bool(row.get("actual"))
        predicted = bool(row.get("predicted"))
        if actual and predicted: tp += 1
        elif not actual and predicted: fp += 1
        elif not actual and not predicted: tn += 1
        else: fn += 1
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {
        "inputSnapshotSha256": digest(payload),
        "metrics": {"truePositive": tp, "falsePositive": fp, "trueNegative": tn,
                    "falseNegative": fn, "precision": round(precision, 8),
                    "recall": round(recall, 8), "f1": round(f1, 8)},
    }


def replay(payload: dict[str, Any]) -> dict[str, Any]:
    original = payload.get("originalResult", {})
    current = payload.get("currentResult", {})
    original_by_subject = {item.get("subjectId"): item for item in original.get("evidence", [])}
    current_by_subject = {item.get("subjectId"): item for item in current.get("evidence", [])}
    changed = []
    for subject_id in sorted(set(original_by_subject) | set(current_by_subject)):
        before = original_by_subject.get(subject_id, {})
        after = current_by_subject.get(subject_id, {})
        if before.get("decision") != after.get("decision") or before.get("score") != after.get("score"):
            changed.append({"subjectId": subject_id, "before": before, "after": after})
    return {"comparisonSha256": digest(payload), "changedCount": len(changed), "changes": changed}


HANDLERS = {
    "/v1/features/snapshot": feature_snapshot,
    "/v1/event-chains/mine": mine_event_chains,
    "/v1/behavior-matrix/build": behavior_matrix,
    "/v1/risk-diffusion/run": risk_diffusion,
    "/v1/incremental-learning/run": incremental_learning,
    "/v1/meta-paths/detect": detect_meta_paths,
    "/v1/temporal-anomalies/detect": detect_temporal_anomalies,
    "/v1/hypergraph/local-infer": infer_local_hypergraph,
    "/v1/cascade/infer": cascade_inference,
    "/v1/case-matters/explain": explain_case,
    "/v1/inference": infer,
    "/v1/evaluations/run": evaluate,
    "/v1/replay": replay,
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path != "/health":
            self.send_error(404)
            return
        self.respond({"status": "UP", "service": "risk-analytics-service", "version": VERSION})

    def do_POST(self) -> None:
        handler = HANDLERS.get(self.path)
        if not handler:
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length) or b"{}")
            self.respond(handler(payload))
        except (ValueError, TypeError, KeyError) as exc:
            self.respond({"error": str(exc)}, status=400)
        except Exception as exc:  # keep the process available, but do not hide failures
            self.respond({"error": f"analytics execution failed: {exc}"}, status=500)

    def respond(self, value: Any, status: int = 200) -> None:
        encoded = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, *_: Any) -> None:
        pass


if __name__ == "__main__":
    host = os.getenv("ANALYTICS_HOST", "0.0.0.0")
    port = int(os.getenv("ANALYTICS_PORT", "18082"))
    ThreadingHTTPServer((host, port), Handler).serve_forever()
