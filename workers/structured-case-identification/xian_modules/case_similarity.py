#!/usr/bin/env python3
"""Approximate undirected heterogeneous-graph GED for case retrieval.

The implementation follows 案例相似度匹配方法.md:
event anchors constrain the mapping, person behavior structures guide person
node alignment, and the final ranking uses only normalized approximate GED.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Set, Tuple

from datagraph_bank.case_library import CaseLibrary

try:
    import numpy as np
except ImportError:  # pragma: no cover - reported at runtime with a clear message
    np = None

try:
    import faiss
except ImportError:  # pragma: no cover - exact NumPy search remains available
    faiss = None


DEFAULT_EMBEDDING_MODEL = "BAAI/bge-small-zh-v1.5"
DEFAULT_RERANKER_MODEL = "BAAI/bge-reranker-v2-m3"
EMBEDDING_RECALL_THRESHOLD = 0.55
ANCHOR_THRESHOLD = 0.50
HIGH_ANCHOR_THRESHOLD = 0.80
PERSON_MAX_SUBSTITUTION = 1.65
MIN_SHARED_EVENT_TYPES = 1
MIN_EVENT_OVERLAP = 0.10
MIN_EVENT_COUNT_RATIO = 0.20
TOP_RESULT_COUNT = 5
SIMILARITY_FINGERPRINT_VERSION = "case-similarity-v6-bge-reranker"

EVENT_RELATIONS = {"顺承关系", "上下位关系", "应对关系"}
ENTITY_EVENT_RELATIONS = {"参与关系", "涉及关系"}
ENTITY_ENTITY_RELATIONS = {"持有关系", "社会关系"}

PROJECT_ROOT = Path(__file__).resolve().parent
DEFAULT_HISTORY_DIR = PROJECT_ROOT / "data" / "case_library" / "cases"
DEFAULT_LIBRARY_ROOT = DEFAULT_HISTORY_DIR.parent
DEFAULT_QUERY_ROOT = (
    PROJECT_ROOT
    / "output"
    / "test_new_extraction"
    / "batch_20260805_172207_621506"
)
DEFAULT_BATCH_OUTPUT_DIR = (
    PROJECT_ROOT / "output" / "test_new_extraction" / "similarity_results"
)


def _resolve_model_source(model_name: str) -> str:
    """Prefer an explicit path, then ModelScope cache, then a remote model ID."""
    model_path = Path(model_name).expanduser()
    modelscope_path = (
        Path.home()
        / ".cache"
        / "modelscope"
        / "models"
        / f"{model_name.replace('/', '--')}"
        / "snapshots"
        / "master"
    )
    return str(
        model_path
        if model_path.exists()
        else modelscope_path
        if modelscope_path.exists()
        else model_name
    )


class SemanticEmbeddingEngine:
    """Cache normalized Chinese sentence embeddings and cosine similarities."""

    def __init__(
        self,
        model_name: str = DEFAULT_EMBEDDING_MODEL,
        device: str = "cpu",
        local_files_only: bool = False,
    ) -> None:
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as exc:  # pragma: no cover - environment dependent
            raise RuntimeError(
                "缺少 sentence-transformers；请先安装 requirements.txt"
            ) from exc
        self.model_name = model_name
        self.device = device
        self.model_source = _resolve_model_source(model_name)
        try:
            self.model = SentenceTransformer(
                self.model_source,
                device=device,
                local_files_only=local_files_only,
            )
        except Exception as exc:  # pragma: no cover - model/network dependent
            raise RuntimeError(f"无法加载中文语义向量模型 {model_name}: {exc}") from exc
        self.cache: Dict[str, Any] = {}

    def preload(self, texts: Iterable[str]) -> None:
        missing = []
        seen = set()
        for value in texts:
            text = clean_text(value)
            if text and text not in self.cache and text not in seen:
                missing.append(text)
                seen.add(text)
        if not missing:
            return
        vectors = self.model.encode(
            missing,
            batch_size=32,
            show_progress_bar=False,
            convert_to_numpy=True,
            normalize_embeddings=True,
        )
        self.cache.update(zip(missing, vectors))

    def cosine_similarity(self, left: str, right: str) -> float:
        left, right = clean_text(left), clean_text(right)
        if not left and not right:
            return 1.0
        if not left or not right:
            return 0.0
        self.preload((left, right))
        score = float(self.cache[left] @ self.cache[right])
        return max(0.0, min(1.0, score))


class RerankerEngine:
    """Score recalled text pairs with a cross-encoder and cache directed scores."""

    def __init__(
        self,
        model_name: str = DEFAULT_RERANKER_MODEL,
        device: str = "cpu",
        local_files_only: bool = False,
        max_length: int = 512,
    ) -> None:
        try:
            import torch
            from transformers import AutoModelForSequenceClassification, AutoTokenizer
        except ImportError as exc:  # pragma: no cover - environment dependent
            raise RuntimeError(
                "缺少 torch/transformers；请先安装 requirements.txt"
            ) from exc
        self.torch = torch
        self.model_name = model_name
        self.device = device
        self.max_length = max_length
        self.model_source = _resolve_model_source(model_name)
        try:
            self.tokenizer = AutoTokenizer.from_pretrained(
                self.model_source,
                local_files_only=local_files_only,
            )
            self.model = AutoModelForSequenceClassification.from_pretrained(
                self.model_source,
                local_files_only=local_files_only,
            ).to(device)
            self.model.eval()
        except Exception as exc:  # pragma: no cover - model/network dependent
            raise RuntimeError(f"无法加载事件精排模型 {model_name}: {exc}") from exc
        self.cache: Dict[Tuple[str, str], float] = {}

    def preload(self, pairs: Iterable[Tuple[str, str]]) -> None:
        missing = []
        seen = set()
        for left_value, right_value in pairs:
            pair = (clean_text(left_value), clean_text(right_value))
            if not pair[0] or not pair[1] or pair in self.cache or pair in seen:
                continue
            missing.append(pair)
            seen.add(pair)
        for start in range(0, len(missing), 8):
            batch = missing[start : start + 8]
            encoded = self.tokenizer(
                [pair[0] for pair in batch],
                [pair[1] for pair in batch],
                padding=True,
                truncation=True,
                max_length=self.max_length,
                return_tensors="pt",
            )
            encoded = {key: value.to(self.device) for key, value in encoded.items()}
            with self.torch.inference_mode():
                logits = self.model(**encoded).logits.reshape(-1)
                scores = self.torch.sigmoid(logits).detach().cpu().tolist()
            for pair, score in zip(batch, scores):
                self.cache[pair] = max(0.0, min(1.0, float(score)))

    def similarity(self, left: str, right: str) -> float:
        left, right = clean_text(left), clean_text(right)
        if not left and not right:
            return 1.0
        if not left or not right:
            return 0.0
        # Average both directions so event similarity remains symmetric.
        self.preload(((left, right), (right, left)))
        return (self.cache[(left, right)] + self.cache[(right, left)]) / 2.0


_semantic_engine: Optional[SemanticEmbeddingEngine] = None
_reranker_engine: Optional[RerankerEngine] = None
_embedding_recall_threshold = EMBEDDING_RECALL_THRESHOLD


def configure_semantic_embedding(
    model_name: str = DEFAULT_EMBEDDING_MODEL,
    device: str = "cpu",
    local_files_only: bool = False,
) -> SemanticEmbeddingEngine:
    """Load and activate the Chinese embedding model once per process."""
    global _semantic_engine
    if (
        _semantic_engine is None
        or _semantic_engine.model_name != model_name
        or _semantic_engine.device != device
    ):
        _semantic_engine = SemanticEmbeddingEngine(
            model_name=model_name,
            device=device,
            local_files_only=local_files_only,
        )
    return _semantic_engine


def clear_semantic_embedding() -> None:
    """Disable semantic scoring, primarily for deterministic unit tests."""
    global _semantic_engine
    _semantic_engine = None


def configure_reranker(
    model_name: str = DEFAULT_RERANKER_MODEL,
    device: str = "cpu",
    local_files_only: bool = False,
    max_length: int = 512,
) -> RerankerEngine:
    """Load and activate the event-pair reranker once per process."""
    global _reranker_engine
    if (
        _reranker_engine is None
        or _reranker_engine.model_name != model_name
        or _reranker_engine.device != device
        or _reranker_engine.max_length != max_length
    ):
        _reranker_engine = RerankerEngine(
            model_name=model_name,
            device=device,
            local_files_only=local_files_only,
            max_length=max_length,
        )
    return _reranker_engine


def clear_reranker() -> None:
    global _reranker_engine
    _reranker_engine = None


@dataclass(frozen=True)
class Node:
    node_id: str
    kind: str  # event, person, entity
    subtype: str
    label: str
    description: str = ""
    attributes: Optional[Dict[str, Any]] = None


@dataclass(frozen=True)
class Edge:
    left: str
    right: str
    relation: str
    description: str = ""


@dataclass
class CaseGraph:
    case_id: str
    case_name: str
    nodes: Dict[str, Node]
    edges: Set[Edge]
    adjacency: Dict[str, List[Tuple[str, str]]]

    @property
    def event_ids(self) -> List[str]:
        return [node_id for node_id, node in self.nodes.items() if node.kind == "event"]

    @property
    def person_ids(self) -> List[str]:
        return [node_id for node_id, node in self.nodes.items() if node.kind == "person"]


def graph_payload(graph: CaseGraph) -> dict:
    """Serialize the complete event-connected graph for result visualization."""
    return {
        "nodes": [
            {
                "id": node.node_id,
                "kind": node.kind,
                "subtype": node.subtype,
                "label": node.label,
                "description": node.description,
                "attributes": node.attributes or {},
            }
            for node in sorted(graph.nodes.values(), key=lambda item: item.node_id)
        ],
        "edges": [
            {
                "left": edge.left,
                "right": edge.right,
                "relation": edge.relation,
                "description": edge.description,
            }
            for edge in sorted(
                graph.edges,
                key=lambda item: (
                    item.left,
                    item.right,
                    item.relation,
                    item.description,
                ),
            )
        ],
    }


def clean_text(value: object) -> str:
    """Normalize a field to a compact string for comparison and output."""
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def text_similarity(left: str, right: str) -> float:
    """Return Chinese embedding cosine similarity in the range 0-1."""
    left, right = clean_text(left), clean_text(right)
    if not left and not right:
        return 1.0
    if not left or not right:
        return 0.0
    if _semantic_engine is None:
        # Direct unit-test/library calls without configured model use exact
        # equality only; production CLI runs always configure the model.
        return 1.0 if left == right else 0.0
    return _semantic_engine.cosine_similarity(left, right)


def event_description_similarity(left: str, right: str) -> float:
    """Recall with BGE-small, then return the cross-encoder reranker score."""
    return event_description_similarity_details(left, right)[
        "description_similarity"
    ]


def event_description_similarity_details(left: str, right: str) -> dict:
    """Return embedding recall and reranker scores for one description pair."""
    embedding_score = text_similarity(left, right)
    recalled = embedding_score >= _embedding_recall_threshold
    reranker_score = (
        _reranker_engine.similarity(left, right)
        if recalled and _reranker_engine is not None
        else None
    )
    score = (
        reranker_score
        if reranker_score is not None
        else embedding_score
        if _reranker_engine is None
        else 0.0
    )
    return {
        "embedding_cosine": (
            round(embedding_score, 4) if _semantic_engine is not None else None
        ),
        "embedding_recall_threshold": _embedding_recall_threshold,
        "recalled_for_reranking": recalled,
        "reranker_score": (
            round(reranker_score, 4) if reranker_score is not None else None
        ),
        "description_similarity": round(score, 4),
        "backend": (
            "bge_small_recall_plus_cross_encoder_reranker"
            if _reranker_engine is not None
            else "embedding_only_test_fallback"
        ),
    }


def description_units(text: str) -> List[str]:
    """Split a description only at explicit sentence or clause boundaries."""
    normalized = clean_text(text)
    if not normalized:
        return []
    return [
        unit.strip()
        for unit in re.split(r"(?<=[。！？!?；;])", normalized)
        if unit.strip()
    ]


def common_description_points(
    left: str,
    right: str,
    limit: int = 3,
    min_similarity: float = 0.70,
) -> List[dict]:
    """Pair complete, semantically similar clauses without cutting text mid-clause."""
    left_units = description_units(left)
    right_units = description_units(right)
    candidates = []
    for left_index, left_unit in enumerate(left_units):
        for right_index, right_unit in enumerate(right_units):
            score_details = event_description_similarity_details(
                left_unit,
                right_unit,
            )
            score = score_details["description_similarity"]
            if score >= min_similarity:
                candidates.append(
                    (
                        -score,
                        left_index,
                        right_index,
                        left_unit,
                        right_unit,
                        score_details,
                    )
                )

    points: List[dict] = []
    used_left: Set[int] = set()
    used_right: Set[int] = set()
    for (
        negative_score,
        left_index,
        right_index,
        left_unit,
        right_unit,
        score_details,
    ) in sorted(candidates, key=lambda item: item[:5]):
        if left_index in used_left or right_index in used_right:
            continue
        used_left.add(left_index)
        used_right.add(right_index)
        points.append(
            {
                "query_text": left_unit,
                "candidate_text": right_unit,
                "similarity": round(-negative_score, 4),
                "embedding_cosine": score_details["embedding_cosine"],
                "reranker_score": score_details["reranker_score"],
            }
        )
        if len(points) >= limit:
            break
    return points


def event_similarity_points(
    query_event: Node,
    candidate_event: Node,
    description_similarity: float,
) -> dict:
    """Build a readable, traceable explanation for one event anchor."""
    same_type = query_event.subtype == candidate_event.subtype
    score_details = event_description_similarity_details(
        query_event.description,
        candidate_event.description,
    )
    common_points = common_description_points(
        query_event.description,
        candidate_event.description,
    )
    similarity_items = [
        (
            f"事件类型一致：均为“{query_event.subtype}”。"
            if same_type
            else (
                f"事件类型分别为“{query_event.subtype}”和"
                f"“{candidate_event.subtype}”。"
            )
        ),
        (
            f"事件描述精排得分：{description_similarity:.4f}"
            f"（BGE-small召回余弦 {score_details['embedding_cosine']:.4f}）。"
            if score_details["reranker_score"] is not None
            else f"事件描述Embedding得分：{description_similarity:.4f}。"
        ),
    ]
    similarity_items.extend(
        (
            f"共同描述 {index}（分句相似度 {point['similarity']:.4f}）：\n"
            f"   查询：“{point['query_text']}”\n"
            f"   候选：“{point['candidate_text']}”"
        )
        for index, point in enumerate(common_points, start=1)
    )
    numbered_points = "\n".join(
        f"{index}. {item}" for index, item in enumerate(similarity_items, start=1)
    )
    summary = (
        f"查询事件：{query_event.label}\n"
        f"查询描述：{query_event.description}\n"
        f"候选事件：{candidate_event.label}\n"
        f"候选描述：{candidate_event.description}\n"
        f"相似点：\n{numbered_points}"
    )
    return {
        "event_type_match": same_type,
        "query_event": {
            "event_id": query_event.node_id,
            "event_name": query_event.label,
            "event_type": query_event.subtype,
            "event_description": query_event.description,
        },
        "candidate_event": {
            "event_id": candidate_event.node_id,
            "event_name": candidate_event.label,
            "event_type": candidate_event.subtype,
            "event_description": candidate_event.description,
        },
        "description_similarity": round(description_similarity, 4),
        "description_similarity_components": score_details,
        "common_description_points": common_points,
        "summary": summary,
    }


def summarize_event_anchors(anchors: Sequence[dict]) -> dict:
    """Summarize all successful event anchors for one case-pair result."""
    points = []
    for anchor in anchors:
        detail = anchor["similar_points"]
        points.append(
            {
            "query_event_id": anchor["query_event_id"],
            "candidate_event_id": anchor["candidate_event_id"],
            "event_type": anchor["event_type"],
            "anchor_level": anchor["anchor_level"],
            "description_similarity": anchor["description_similarity"],
            "description_similarity_components": detail[
                "description_similarity_components"
            ],
            "event_type_match": detail["event_type_match"],
            "query_event": detail["query_event"],
            "candidate_event": detail["candidate_event"],
            "common_description_points": detail["common_description_points"],
            "summary": detail["summary"],
        }
        )
    high_count = sum(anchor["anchor_level"] == "high" for anchor in anchors)
    candidate_count = len(anchors) - high_count
    if anchors:
        summary = (
            f"共 {len(anchors)} 个事件锚点：高置信度 {high_count} 个，"
            f"普通候选 {candidate_count} 个。"
        )
    else:
        summary = "未形成满足事件类型一致且描述相似度达到阈值的事件锚点。"
    return {
        "anchor_count": len(anchors),
        "high_anchor_count": high_count,
        "candidate_anchor_count": candidate_count,
        "summary": summary,
        "similar_points": points,
    }


def canonical_edge(left: str, right: str, relation: str, description: str = "") -> Edge:
    """Create an undirected edge with a stable endpoint order."""
    if left <= right:
        return Edge(left, right, relation, clean_text(description))
    return Edge(right, left, relation, clean_text(description))


def entity_node(
    entity_id: str,
    kind: str,
    subtype: str,
    label: str,
    description: str = "",
    attributes: Optional[Dict[str, Any]] = None,
) -> Node:
    """Construct a normalized entity/person node."""
    return Node(
        entity_id,
        kind,
        clean_text(subtype),
        clean_text(label),
        clean_text(description),
        dict(attributes or {}),
    )


def is_person_other_entity(item: dict) -> bool:
    """Decide whether an ``other_entities`` record represents a person."""
    entity_id = clean_text(item.get("entity_id"))
    subtype = clean_text(item.get("entity_attr_2"))
    description = clean_text(item.get("entity_attr_3"))
    return entity_id.upper().startswith("PER") or "个人" in subtype or "个人" in description or "交易对手" in subtype


def load_case(path: Path) -> CaseGraph:
    """Load one extracted case and retain only its event-connected subgraph.

    Components that cannot be reached from any event are excluded because the
    current method uses event-linked behavior as the evidence for matching.
    """
    data = json.loads(path.read_text(encoding="utf-8"))
    basic = data.get("basic_info") or {}
    case_id = clean_text(basic.get("case_id")) or path.stem
    case_name = clean_text(basic.get("case_name")) or path.name
    nodes: Dict[str, Node] = {}

    for item in data.get("customers") or []:
        node_id = clean_text(item.get("entity_id"))
        if node_id:
            nodes[node_id] = entity_node(
                node_id,
                "person",
                clean_text(item.get("occupation_industry")) or "客户",
                clean_text(item.get("customer_name")) or node_id,
                clean_text(item.get("customer_risk_level")),
                item,
            )

    for item in data.get("accounts") or []:
        node_id = clean_text(item.get("entity_id"))
        if node_id:
            nodes[node_id] = entity_node(
                node_id,
                "entity",
                clean_text(item.get("account_type")) or "账户",
                node_id,
                clean_text(item.get("holder_name")),
                item,
            )

    for item in data.get("other_entities") or []:
        node_id = clean_text(item.get("entity_id"))
        if node_id:
            subtype = clean_text(item.get("entity_attr_2")) or clean_text(item.get("entity_attr_3")) or "其他实体"
            nodes[node_id] = entity_node(
                node_id,
                "person" if is_person_other_entity(item) else "entity",
                subtype,
                clean_text(item.get("entity_attr_1")) or node_id,
                clean_text(item.get("entity_attr_3")) + " " + clean_text(item.get("entity_attr_4")),
                item,
            )

    for item in data.get("events") or []:
        node_id = clean_text(item.get("event_id"))
        if node_id:
            nodes[node_id] = Node(
                node_id,
                "event",
                clean_text(item.get("event_type")),
                clean_text(item.get("event_name")) or node_id,
                clean_text(item.get("event_description")),
                dict(item),
            )

    edges: Set[Edge] = set()
    for relation in data.get("relationships") or []:
        relation_type = clean_text(relation.get("relationship_type"))
        relation_description = clean_text(relation.get("relationship_description"))
        left = clean_text(relation.get("source_node_id"))
        right = clean_text(relation.get("target_node_id"))
        if not left or not right or left not in nodes or right not in nodes:
            continue
        left_kind, right_kind = nodes[left].kind, nodes[right].kind
        if relation_type in ENTITY_ENTITY_RELATIONS and left_kind != "event" and right_kind != "event":
            edges.add(canonical_edge(left, right, relation_type, relation_description))
        elif relation_type in ENTITY_EVENT_RELATIONS and {left_kind, right_kind} == {"event", "person"} or relation_type in ENTITY_EVENT_RELATIONS and "event" in {left_kind, right_kind} and left_kind != right_kind:
            edges.add(canonical_edge(left, right, relation_type, relation_description))
        elif relation_type in EVENT_RELATIONS and left_kind == "event" and right_kind == "event":
            edges.add(canonical_edge(left, right, relation_type, relation_description))

    # GED only considers the event-connected subgraph. Nodes that cannot be
    # reached from any event have no behavioral evidence for case matching.
    event_ids = {node_id for node_id, node in nodes.items() if node.kind == "event"}
    event_connected = set(event_ids)
    frontier = list(event_ids)
    while frontier:
        current = frontier.pop()
        for edge in edges:
            if edge.left == current:
                neighbor = edge.right
            elif edge.right == current:
                neighbor = edge.left
            else:
                continue
            if neighbor not in event_connected:
                event_connected.add(neighbor)
                frontier.append(neighbor)

    nodes = {node_id: node for node_id, node in nodes.items() if node_id in event_connected}
    edges = {
        edge for edge in edges
        if edge.left in event_connected and edge.right in event_connected
    }

    adjacency: Dict[str, List[Tuple[str, str]]] = defaultdict(list)
    for edge in edges:
        adjacency[edge.left].append((edge.right, edge.relation))
        adjacency[edge.right].append((edge.left, edge.relation))

    return CaseGraph(case_id, case_name, nodes, edges, dict(adjacency))


def event_substitution_cost(left: Node, right: Node) -> float:
    """Return the replacement cost for two event nodes.

    For the same event type, the blended description score contributes
    continuously as ``1 - similarity``. Different event types retain a base
    penalty of 1.0 plus up to 0.5 for description dissimilarity. Event IDs are
    not compared.
    """
    description_score = event_description_similarity(
        left.description,
        right.description,
    )
    if left.subtype == right.subtype:
        return 1.0 - description_score
    return 1.0 + 0.5 * (1.0 - description_score)


def relation_profile(graph: CaseGraph, node_id: str) -> Counter:
    """Count relation types incident to a node."""
    return Counter(relation for _, relation in graph.adjacency.get(node_id, []))


def event_profile(graph: CaseGraph, node_id: str) -> Counter:
    """Count event-neighbor type/relation pairs for a node."""
    profile = Counter()
    for neighbor, relation in graph.adjacency.get(node_id, []):
        node = graph.nodes[neighbor]
        if node.kind == "event":
            profile[(node.subtype, relation)] += 1
    return profile


def neighboring_subtypes(graph: CaseGraph, node_id: str) -> Counter:
    """Count neighboring node-kind, subtype, and relation triples."""
    return Counter(
        (graph.nodes[neighbor].kind, graph.nodes[neighbor].subtype, relation)
        for neighbor, relation in graph.adjacency.get(node_id, [])
    )


def person_event_edges(graph: CaseGraph, node_id: str) -> List[Tuple[str, str]]:
    """Return a person's undirected links to event nodes."""
    return [
        (neighbor, relation)
        for neighbor, relation in graph.adjacency.get(node_id, [])
        if graph.nodes[neighbor].kind == "event" and relation in ENTITY_EVENT_RELATIONS
    ]


def person_non_event_neighbors(graph: CaseGraph, node_id: str) -> Counter:
    """Count a person's non-event neighbors and their relation types."""
    return Counter(
        (graph.nodes[neighbor].kind, graph.nodes[neighbor].subtype, relation)
        for neighbor, relation in graph.adjacency.get(node_id, [])
        if graph.nodes[neighbor].kind != "event"
    )


def event_chain_signature(graph: CaseGraph, event_id: str) -> Counter:
    """Undirected local event-structure signature; no edge direction is used."""
    return Counter(
        (graph.nodes[neighbor].kind, graph.nodes[neighbor].subtype, relation)
        for neighbor, relation in graph.adjacency.get(event_id, [])
        if graph.nodes[neighbor].kind == "event" and relation in EVENT_RELATIONS
    )


def person_event_alignment_score(
    left_graph: CaseGraph,
    left_id: str,
    right_graph: CaseGraph,
    right_id: str,
    anchor_event_mapping: Dict[str, str],
) -> float:
    """Score person-event links that survive the event-anchor mapping.

    For each query person-event link, the corresponding anchored candidate
    event must be connected to the candidate person. Equal relation types
    score 1.0; the compatible ``参与关系``/``涉及关系`` pair scores 0.5. The
    denominator is the larger number of event links in either person.
    """
    left_edges = person_event_edges(left_graph, left_id)
    right_edges = person_event_edges(right_graph, right_id)
    if not left_edges or not right_edges:
        return 0.0
    right_by_event = defaultdict(list)
    for event_id, relation in right_edges:
        right_by_event[event_id].append(relation)
    matched = 0.0
    for left_event, left_relation in left_edges:
        mapped_event = anchor_event_mapping.get(left_event)
        if mapped_event not in right_by_event:
            continue
        relation_scores = [1.0 if left_relation == item else 0.5 for item in right_by_event[mapped_event]]
        matched += max(relation_scores)
    return matched / max(len(left_edges), len(right_edges))


def person_relation_type_score(left_graph: CaseGraph, left_id: str, right_graph: CaseGraph, right_id: str) -> float:
    """Compare the multisets of a person's event-link relation types."""
    left = Counter(relation for _, relation in person_event_edges(left_graph, left_id))
    right = Counter(relation for _, relation in person_event_edges(right_graph, right_id))
    return counter_similarity(left, right)


def anchored_relation_type_score(
    left_graph: CaseGraph,
    left_id: str,
    right_graph: CaseGraph,
    right_id: str,
    anchor_event_mapping: Dict[str, str],
) -> float:
    """Compare only relation types attached to corresponding anchored events."""
    left = Counter(
        relation
        for event_id, relation in person_event_edges(left_graph, left_id)
        if event_id in anchor_event_mapping
    )
    mapped_events = set(anchor_event_mapping.values())
    right = Counter(
        relation
        for event_id, relation in person_event_edges(right_graph, right_id)
        if event_id in mapped_events
    )
    return counter_similarity(left, right)


def person_position_score(
    left_graph: CaseGraph,
    left_id: str,
    right_graph: CaseGraph,
    right_id: str,
    anchor_event_mapping: Dict[str, str],
) -> float:
    """Compare event-neighborhood signatures around mapped person events."""
    right_events = {event_id for event_id, _ in person_event_edges(right_graph, right_id)}
    matched_left_events = {
        anchor_event_mapping[event_id]
        for event_id, _ in person_event_edges(left_graph, left_id)
        if event_id in anchor_event_mapping and anchor_event_mapping[event_id] in right_events
    }
    if not matched_left_events:
        return 0.0
    scores = []
    for left_event, _ in person_event_edges(left_graph, left_id):
        right_event = anchor_event_mapping.get(left_event)
        if right_event not in matched_left_events:
            continue
        scores.append(counter_similarity(event_chain_signature(left_graph, left_event), event_chain_signature(right_graph, right_event)))
    return sum(scores) / len(scores) if scores else 0.0


def person_neighbor_score(left_graph: CaseGraph, left_id: str, right_graph: CaseGraph, right_id: str) -> float:
    """Compare non-event neighborhood structure; retained for diagnostics."""
    return counter_similarity(person_non_event_neighbors(left_graph, left_id), person_non_event_neighbors(right_graph, right_id))


def counter_similarity(left: Counter, right: Counter) -> float:
    """Return multiset overlap similarity using min/max counts."""
    keys = set(left) | set(right)
    if not keys:
        return 1.0
    numerator = sum(min(left[key], right[key]) for key in keys)
    denominator = sum(max(left[key], right[key]) for key in keys)
    return numerator / denominator if denominator else 1.0


def event_type_counts(graph: CaseGraph) -> Counter:
    """Count event nodes by event type for vector retrieval and filtering."""
    return Counter(graph.nodes[node_id].subtype for node_id in graph.event_ids)


def faiss_event_type_search(
    query: CaseGraph,
    candidates: Sequence[Tuple[Path, CaseGraph]],
    top_k: int,
) -> List[Tuple[Path, CaseGraph, float]]:
    """Retrieve cases by event-type count vectors before running GED.

    L2-normalized count vectors make FAISS inner product equal cosine similarity.
    The NumPy branch is an exact fallback for environments without faiss-cpu.
    """
    if not candidates or top_k <= 0:
        return []
    vocabulary = sorted(
        set(event_type_counts(query))
        | {event_type for _, graph in candidates for event_type in event_type_counts(graph)}
    )
    if np is None:
        query_counts = event_type_counts(query)
        query_norm = math.sqrt(sum(value * value for value in query_counts.values()))
        ranked = []
        for index, (path, graph) in enumerate(candidates):
            candidate_counts = event_type_counts(graph)
            candidate_norm = math.sqrt(
                sum(value * value for value in candidate_counts.values())
            )
            denominator = query_norm * candidate_norm
            score = (
                sum(
                    query_counts[event_type] * candidate_counts[event_type]
                    for event_type in vocabulary
                )
                / denominator
                if denominator
                else 0.0
            )
            ranked.append((score, graph.case_id, index, path, graph))
        ranked.sort(key=lambda item: (-item[0], item[1], item[2]))
        return [
            (path, graph, score)
            for score, _, _, path, graph in ranked[: min(top_k, len(ranked))]
        ]

    index_by_type = {event_type: index for index, event_type in enumerate(vocabulary)}

    def vectorize(graph: CaseGraph) -> np.ndarray:
        vector = np.zeros(len(vocabulary), dtype="float32")
        for event_type, count in event_type_counts(graph).items():
            vector[index_by_type[event_type]] = float(count)
        norm = np.linalg.norm(vector)
        if norm > 0:
            vector /= norm
        return vector

    query_vector = vectorize(query).reshape(1, -1)
    matrix = np.vstack([vectorize(graph) for _, graph in candidates])
    limit = min(top_k, len(candidates))

    if faiss is not None:
        search_index = faiss.IndexFlatIP(matrix.shape[1])
        search_index.add(matrix)
        scores, indices = search_index.search(query_vector, limit)
        ranked = zip(indices[0].tolist(), scores[0].tolist())
    else:
        scores = matrix @ query_vector[0]
        ranked = ((int(index), float(scores[index])) for index in np.argsort(-scores)[:limit])

    return [(candidates[index][0], candidates[index][1], score) for index, score in ranked if index >= 0]


def event_prefilter(
    query: CaseGraph,
    candidate: CaseGraph,
    min_shared_types: int = MIN_SHARED_EVENT_TYPES,
    min_overlap: float = MIN_EVENT_OVERLAP,
    min_count_ratio: float = MIN_EVENT_COUNT_RATIO,
) -> dict:
    """Coarse recall filter; it is not used as the final similarity metric."""
    query_counts = event_type_counts(query)
    candidate_counts = event_type_counts(candidate)
    shared_types = set(query_counts) & set(candidate_counts)
    shared_event_count = sum(min(query_counts[item], candidate_counts[item]) for item in shared_types)
    query_event_count = sum(query_counts.values())
    candidate_event_count = sum(candidate_counts.values())
    overlap = shared_event_count / min(query_event_count, candidate_event_count) if query_event_count and candidate_event_count else 0.0
    count_ratio = min(query_event_count, candidate_event_count) / max(query_event_count, candidate_event_count) if query_event_count and candidate_event_count else 0.0
    passed = (
        len(shared_types) >= min_shared_types
        and overlap >= min_overlap
        and count_ratio >= min_count_ratio
    )
    return {
        "passed": passed,
        "shared_event_types": sorted(shared_types),
        "shared_event_type_count": len(shared_types),
        "shared_event_count": shared_event_count,
        "query_event_count": query_event_count,
        "candidate_event_count": candidate_event_count,
        "multiset_overlap": round(overlap, 6),
        "event_count_ratio": round(count_ratio, 6),
    }


def person_substitution_cost(
    left_graph: CaseGraph,
    left_id: str,
    right_graph: CaseGraph,
    right_id: str,
    anchor_event_mapping: Dict[str, str],
) -> float:
    """Calculate a person's replacement cost from anchored behavior.

    The score is ``0.70 * anchored_event_alignment + 0.30 * anchored_relation``
    and the cost is ``1 - score``. Only links to corresponding anchored events
    are used; links to remaining, unanchored events contribute 0. If no
    anchored event link exists, return 2.0 so this pair cannot be accepted as a
    person substitution by the current threshold.
    """
    anchor_score = person_event_alignment_score(left_graph, left_id, right_graph, right_id, anchor_event_mapping)
    relation_score = anchored_relation_type_score(
        left_graph, left_id, right_graph, right_id, anchor_event_mapping
    )

    # A person can only be aligned through an event anchor. Remaining event
    # links intentionally contribute zero, as specified by the method.
    if anchor_score == 0.0:
        return 2.0

    score = 0.70 * anchor_score + 0.30 * relation_score
    return 1.0 - score


def greedy_event_mapping(left: CaseGraph, right: CaseGraph) -> Tuple[Dict[str, str], List[dict]]:
    """Greedily create one-to-one event mappings from anchor candidates."""
    candidates = []
    for left_id in left.event_ids:
        for right_id in right.event_ids:
            cost = event_substitution_cost(left.nodes[left_id], right.nodes[right_id])
            description_score = event_description_similarity(
                left.nodes[left_id].description,
                right.nodes[right_id].description,
            )
            same_type = left.nodes[left_id].subtype == right.nodes[right_id].subtype
            if same_type and description_score >= ANCHOR_THRESHOLD:
                candidates.append((cost, -description_score, left_id, right_id, description_score))

    mapping: Dict[str, str] = {}
    used_right: Set[str] = set()
    anchors = []
    for cost, negative_score, left_id, right_id, description_score in sorted(candidates):
        if left_id in mapping or right_id in used_right:
            continue
        mapping[left_id] = right_id
        used_right.add(right_id)
        anchors.append(
            {
                "query_event_id": left_id,
                "candidate_event_id": right_id,
                "event_type": left.nodes[left_id].subtype,
                "description_similarity": round(description_score, 4),
                "anchor_level": "high" if description_score >= HIGH_ANCHOR_THRESHOLD else "candidate",
                "similar_points": event_similarity_points(
                    left.nodes[left_id],
                    right.nodes[right_id],
                    description_score,
                ),
            }
        )
    return mapping, anchors


def complete_event_mapping(left: CaseGraph, right: CaseGraph, mapping: Dict[str, str]) -> Dict[str, str]:
    """Add low-cost one-to-one event substitutions after anchors."""
    used_right = set(mapping.values())
    remaining_left = [node_id for node_id in left.event_ids if node_id not in mapping]
    remaining_right = [node_id for node_id in right.event_ids if node_id not in used_right]
    candidates = []
    for left_id in remaining_left:
        for right_id in remaining_right:
            candidates.append((event_substitution_cost(left.nodes[left_id], right.nodes[right_id]), left_id, right_id))
    for cost, left_id, right_id in sorted(candidates):
        if left_id in mapping or right_id in used_right:
            continue
        if cost <= 1.5:
            mapping[left_id] = right_id
            used_right.add(right_id)
    return mapping


def greedy_person_mapping(left: CaseGraph, right: CaseGraph, anchor_event_mapping: Dict[str, str]) -> Dict[str, str]:
    """Greedily map person nodes using anchored behavior costs.

    This is an approximation: it accepts a pair when its substitution cost is
    at most ``PERSON_MAX_SUBSTITUTION``. It does not globally optimize the
    comparison against deleting the left person and inserting the right one.
    """
    candidates = []
    for left_id in left.person_ids:
        for right_id in right.person_ids:
            cost = person_substitution_cost(left, left_id, right, right_id, anchor_event_mapping)
            candidates.append((cost, left_id, right_id))
    mapping: Dict[str, str] = {}
    used_right: Set[str] = set()
    for cost, left_id, right_id in sorted(candidates):
        if left_id in mapping or right_id in used_right:
            continue
        # The current method uses a thresholded greedy decision rather than
        # comparing every pair globally with delete-cost + insert-cost.
        if cost <= PERSON_MAX_SUBSTITUTION:
            mapping[left_id] = right_id
            used_right.add(right_id)
    return mapping


def generic_entity_cost(left: Node, right: Node) -> float:
    """Return replacement cost for non-person entities by kind, subtype, label."""
    if left.kind != right.kind:
        return 2.0
    subtype_score = 1.0 if left.subtype == right.subtype and left.subtype else 0.0
    label_score = text_similarity(left.label, right.label)
    return 1.0 - (0.75 * subtype_score + 0.25 * label_score)


def map_remaining_entities(left: CaseGraph, right: CaseGraph, mapping: Dict[str, str]) -> Dict[str, str]:
    """Greedily map remaining non-person entities under a cost threshold."""
    left_ids = [node_id for node_id, node in left.nodes.items() if node.kind == "entity" and node_id not in mapping]
    right_ids = [node_id for node_id, item in right.nodes.items() if item.kind == "entity" and node_id not in mapping.values()]
    candidates = []
    for left_id in left_ids:
        for right_id in right_ids:
            candidates.append((generic_entity_cost(left.nodes[left_id], right.nodes[right_id]), left_id, right_id))
    used_right = set(mapping.values())
    for cost, left_id, right_id in sorted(candidates):
        if left_id in mapping or right_id in used_right:
            continue
        # If the pair is too dissimilar, leave both nodes unmapped. They will
        # later be counted as one query deletion and/or one candidate insertion.
        if cost <= 1.5:
            mapping[left_id] = right_id
            used_right.add(right_id)
    return mapping


def edge_cost(relation_left: str, relation_right: Optional[str]) -> float:
    """Return undirected relation edit cost.

    ``relation_right is None`` means an edge deletion or insertion. Relation
    direction is never considered. Same relation costs 0, the compatible
    participation/involvement pair costs 0.5, and other relation changes cost
    0.8. Edge descriptions only affect candidate tie-breaking elsewhere.
    """
    if relation_right is None:
        return {"参与关系": 1.0, "涉及关系": 1.0, "顺承关系": 1.2, "上下位关系": 1.2, "应对关系": 1.2}.get(relation_left, 0.8)
    if relation_left == relation_right:
        return 0.0
    if {relation_left, relation_right} == {"参与关系", "涉及关系"}:
        return 0.5
    if relation_left in EVENT_RELATIONS and relation_right in EVENT_RELATIONS:
        return 0.8
    if relation_left in ENTITY_ENTITY_RELATIONS and relation_right in ENTITY_ENTITY_RELATIONS:
        return 0.8
    return 0.8


def full_graph_edit_cost(graph: CaseGraph) -> float:
    """Return the cost of deleting or inserting every node and edge in a graph."""
    node_cost = sum(
        1.5 if node.kind == "event" else 1.0
        for node in graph.nodes.values()
    )
    relation_cost = sum(edge_cost(edge.relation, None) for edge in graph.edges)
    return node_cost + relation_cost


def approximate_ged(left: CaseGraph, right: CaseGraph) -> Tuple[float, dict]:
    """Compute the current greedy approximate GED and its edit breakdown.

    Unmapped query nodes/edges are deletions. Candidate nodes/edges not covered
    by the mapping are insertions. Mapped nodes and covered edges are compared
    using their substitution costs. The returned first value is normalized GED;
    the detail dictionary also contains raw GED and every edit category.
    """
    anchor_event_mapping, anchors = greedy_event_mapping(left, right)
    event_mapping = dict(anchor_event_mapping)
    event_mapping = complete_event_mapping(left, right, event_mapping)
    mapping = dict(event_mapping)
    mapping.update(greedy_person_mapping(left, right, anchor_event_mapping))
    mapping = map_remaining_entities(left, right, mapping)

    mapped_right = set(mapping.values())
    node_cost = 0.0
    node_cost_breakdown = Counter()
    node_edits = {"event_substitution": 0, "person_substitution": 0, "entity_substitution": 0, "node_deletion": 0, "node_insertion": 0}

    # A mapped pair is a substitution; an unmapped query node is a deletion.
    for left_id, left_node in left.nodes.items():
        right_id = mapping.get(left_id)
        if right_id is None:
            cost = 1.5 if left_node.kind == "event" else 1.0
            node_cost += cost
            node_cost_breakdown[f"{left_node.kind}_deletion"] += cost
            node_edits["node_deletion"] += 1
            continue
        right_node = right.nodes[right_id]
        if left_node.kind == "event":
            cost = event_substitution_cost(left_node, right_node)
            node_edits["event_substitution"] += int(cost > 0)
        elif left_node.kind == "person":
            cost = person_substitution_cost(left, left_id, right, right_id, anchor_event_mapping)
            node_edits["person_substitution"] += int(cost > 0)
        else:
            cost = generic_entity_cost(left_node, right_node)
            node_edits["entity_substitution"] += int(cost > 0)
        node_cost += cost
        node_cost_breakdown[f"{left_node.kind}_substitution"] += cost

    # Candidate nodes not covered by the one-to-one mapping are insertions.
    for right_id, right_node in right.nodes.items():
        if right_id not in mapped_right:
            cost = 1.5 if right_node.kind == "event" else 1.0
            node_cost += cost
            node_cost_breakdown[f"{right_node.kind}_insertion"] += cost
            node_edits["node_insertion"] += 1

    edge_cost_total = 0.0
    edge_cost_breakdown = Counter()
    edge_edits = {"edge_substitution": 0, "edge_deletion": 0, "edge_insertion": 0}
    matched_edges = []
    unmatched_left_edges = []
    unmatched_right_edges = []
    covered_right_edges: Set[Edge] = set()
    right_edge_by_pair: Dict[Tuple[str, str], List[Edge]] = defaultdict(list)
    for edge in right.edges:
        right_edge_by_pair[(edge.left, edge.right)].append(edge)

    # An edge can be preserved/substituted only after both endpoints are mapped.
    for edge in left.edges:
        mapped_left, mapped_right_id = mapping.get(edge.left), mapping.get(edge.right)
        if mapped_left is None or mapped_right_id is None:
            # If either endpoint is deleted, this query edge is deleted too.
            cost = edge_cost(edge.relation, None)
            edge_cost_total += cost
            edge_cost_breakdown["edge_deletion"] += cost
            edge_edits["edge_deletion"] += 1
            unmatched_left_edges.append({"left": edge.left, "right": edge.right, "relation": edge.relation})
            continue
        pair = tuple(sorted((mapped_left, mapped_right_id)))
        candidates = [candidate for candidate in right_edge_by_pair.get(pair, []) if candidate not in covered_right_edges]
        if candidates:
            candidate = min(
                candidates,
                key=lambda item: (
                    edge_cost(edge.relation, item.relation),
                    -text_similarity(edge.description, item.description),
                    item.relation,
                    item.description,
                ),
            )
            covered_right_edges.add(candidate)
            cost = edge_cost(edge.relation, candidate.relation)
            edge_cost_total += cost
            edge_cost_breakdown["edge_substitution"] += cost
            edge_edits["edge_substitution"] += int(cost > 0)
            matched_edges.append(
                {
                    "query_edge": {
                        "left": edge.left,
                        "right": edge.right,
                        "relation": edge.relation,
                        "description": edge.description,
                    },
                    "candidate_edge": {
                        "left": candidate.left,
                        "right": candidate.right,
                        "relation": candidate.relation,
                        "description": candidate.description,
                    },
                    "edit_cost": round(cost, 6),
                    "match_type": "preserved" if cost == 0 else "substituted",
                }
            )
        else:
            cost = edge_cost(edge.relation, None)
            edge_cost_total += cost
            edge_cost_breakdown["edge_deletion"] += cost
            edge_edits["edge_deletion"] += 1
            unmatched_left_edges.append({"left": edge.left, "right": edge.right, "relation": edge.relation})

    # Candidate edges not covered by a query edge are insertions.
    for edge in right.edges:
        if edge not in covered_right_edges:
            cost = edge_cost(edge.relation, None)
            edge_cost_total += cost
            edge_cost_breakdown["edge_insertion"] += cost
            edge_edits["edge_insertion"] += 1
            unmatched_right_edges.append({"left": edge.left, "right": edge.right, "relation": edge.relation})

    raw_ged = node_cost + edge_cost_total
    # Complete deletion of the query graph plus complete insertion of the
    # candidate graph is a valid edit path and therefore a meaningful upper
    # bound for the current weighted GED. Clamp defensively against floating
    # point drift and future cost-table changes so similarity always stays in
    # the closed interval [0, 1].
    normalization_upper_bound = full_graph_edit_cost(left) + full_graph_edit_cost(right)
    unbounded_normalized = raw_ged / normalization_upper_bound if normalization_upper_bound else 0.0
    normalized = min(1.0, max(0.0, unbounded_normalized))
    similarity = min(1.0, max(0.0, 1.0 - normalized))
    return normalized, {
        "ged": round(raw_ged, 6),
        "node_cost": round(node_cost, 6),
        "edge_cost": round(edge_cost_total, 6),
        "node_cost_breakdown": {key: round(value, 6) for key, value in node_cost_breakdown.items()},
        "edge_cost_breakdown": {key: round(value, 6) for key, value in edge_cost_breakdown.items()},
        "matched_nodes": [
            {
                "match_index": index,
                "query_id": left_id,
                "candidate_id": right_id,
                "query_kind": left.nodes[left_id].kind,
                "candidate_kind": right.nodes[right_id].kind,
                "query_subtype": left.nodes[left_id].subtype,
                "candidate_subtype": right.nodes[right_id].subtype,
                "query_label": left.nodes[left_id].label,
                "candidate_label": right.nodes[right_id].label,
                "query_description": left.nodes[left_id].description,
                "candidate_description": right.nodes[right_id].description,
                "query_attributes": left.nodes[left_id].attributes or {},
                "candidate_attributes": right.nodes[right_id].attributes or {},
            }
            for index, (left_id, right_id) in enumerate(
                sorted(mapping.items()), start=1
            )
        ],
        "matched_edges": sorted(
            matched_edges,
            key=lambda item: (
                item["query_edge"]["left"],
                item["query_edge"]["right"],
                item["query_edge"]["relation"],
            ),
        ),
        "unmatched_query_nodes": [
            {
                "id": node_id,
                "kind": node.kind,
                "subtype": node.subtype,
                "label": node.label,
            }
            for node_id, node in left.nodes.items()
            if node_id not in mapping
        ],
        "unmatched_candidate_nodes": [
            {
                "id": node_id,
                "kind": node.kind,
                "subtype": node.subtype,
                "label": node.label,
            }
            for node_id, node in right.nodes.items()
            if node_id not in mapped_right
        ],
        "unmatched_query_edges": unmatched_left_edges,
        "unmatched_candidate_edges": unmatched_right_edges,
        "normalization_upper_bound": round(normalization_upper_bound, 6),
        "normalized_ged": round(normalized, 6),
        "similarity": round(similarity, 6),
        "event_anchors": anchors,
        "node_mapping_count": len(mapping),
        "matched_edge_count": len(matched_edges),
        "node_edits": node_edits,
        "edge_edits": edge_edits,
        "query_graph": {"nodes": len(left.nodes), "edges": len(left.edges)},
        "candidate_graph": {"nodes": len(right.nodes), "edges": len(right.edges)},
    }


def build_match_summary(
    query: CaseGraph,
    candidate: CaseGraph,
    vector_similarity: Optional[float],
    details: dict,
) -> str:
    """Build a Chinese plain-text explanation of one case match.

    The text summarizes the retrieval score, prefilter result, node/edge edit
    counts, and raw/normalized GED so users can read the result without
    inspecting every JSON field. It is descriptive only and does not change
    the GED calculation.
    """
    node_edits = details.get("node_edits", {})
    edge_edits = details.get("edge_edits", {})
    prefilter = details.get("prefilter", {})
    vector_text = "未执行" if vector_similarity is None else f"{vector_similarity:.6f}"
    node_substitution_cost = sum(
        details.get("node_cost_breakdown", {}).get(key, 0.0)
        for key in ("person_substitution", "entity_substitution", "event_substitution")
    )
    return (
        f"匹配汇总：查询案例“{query.case_name}”与候选案例“{candidate.case_name}”"
        f"的事件类型向量余弦相似度为 {vector_text}，"
        f"粗筛{'通过' if prefilter.get('passed') else '未通过'}；"
        f"共享事件类型 {prefilter.get('shared_event_type_count', 0)} 类，"
        f"多重集合重叠率 {prefilter.get('multiset_overlap', 0):.4f}。"
        f"原始 GED 为 {details.get('ged', 0):.6f}，"
        f"归一化 GED 为 {details.get('normalized_ged', 0):.6f}，"
        f"相似度为 {details.get('similarity', 0):.6f}；"
        f"事件锚定 {len(details.get('event_anchors', []))} 个，"
        f"节点映射 {details.get('node_mapping_count', 0)} 个。"
        f"节点替换成本 {node_substitution_cost:.6f}，"
        f"节点删除 {node_edits.get('node_deletion', 0)} 个，"
        f"节点增加 {node_edits.get('node_insertion', 0)} 个；"
        f"关系替换 {edge_edits.get('edge_substitution', 0)} 条，"
        f"关系删除 {edge_edits.get('edge_deletion', 0)} 条，"
        f"关系增加 {edge_edits.get('edge_insertion', 0)} 条。"
        "GED 越小表示结构差异越小，归一化 GED 越接近 0 表示越相似。"
    )


def candidate_paths(candidate: Optional[str], case_dir: Optional[str], query: Path) -> List[Path]:
    """Collect candidate JSON paths while excluding the query file and duplicates."""
    paths: List[Path] = []
    if candidate:
        paths.append(Path(candidate))
    if case_dir:
        paths.extend(sorted(Path(case_dir).glob("*.json")))
    unique = []
    seen = set()
    for path in paths:
        resolved = path.resolve()
        if resolved == query.resolve() or resolved in seen:
            continue
        if path.is_file():
            unique.append(path)
            seen.add(resolved)
    return unique


def load_history_library(path: Path) -> List[Tuple[str, CaseGraph]]:
    """Load historical cases from a folder containing one JSON per case.

    For backward compatibility, a JSON list, an object containing ``cases``,
    or a single case object is also accepted. In the folder mode, every
    ``*.json`` file is loaded independently as one historical case.
    """
    if path.is_dir():
        return [(str(item), load_case(item)) for item in sorted(path.glob('*.json'))]

    raw = json.loads(path.read_text(encoding='utf-8'))
    if isinstance(raw, list):
        records = raw
    elif isinstance(raw, dict) and isinstance(raw.get('cases'), list):
        records = raw['cases']
    elif isinstance(raw, dict) and 'events' in raw:
        records = [raw]
    else:
        raise ValueError('历史案例库 JSON 必须是案例数组、包含 cases 数组，或单个案例对象')

    loaded = []
    for index, record in enumerate(records):
        if not isinstance(record, dict):
            continue
        temp_path = path.parent / f'.__history_case_{index}.json'
        temp_path.write_text(json.dumps(record, ensure_ascii=False), encoding='utf-8')
        try:
            loaded.append((f'{path}#cases[{index}]', load_case(temp_path)))
        finally:
            temp_path.unlink(missing_ok=True)
    return loaded


def configure_semantic_embedding_from_args(args: argparse.Namespace) -> None:
    """Apply BGE recall and cross-encoder reranker settings."""
    global _embedding_recall_threshold
    model_name = getattr(args, "embedding_model", None)
    if model_name:
        configure_semantic_embedding(
            model_name=str(model_name),
            device=str(getattr(args, "embedding_device", "cpu")),
            local_files_only=bool(
                getattr(args, "embedding_local_files_only", False)
            ),
        )
    reranker_name = getattr(args, "reranker_model", None)
    if reranker_name:
        configure_reranker(
            model_name=str(reranker_name),
            device=str(getattr(args, "reranker_device", "cpu")),
            local_files_only=bool(
                getattr(args, "reranker_local_files_only", False)
            ),
            max_length=int(getattr(args, "reranker_max_length", 512)),
        )
    _embedding_recall_threshold = float(
        getattr(args, "embedding_recall_threshold", EMBEDDING_RECALL_THRESHOLD)
    )


def match_query(
    query_path: Path,
    candidates: Sequence[Tuple[Path, CaseGraph]],
    args: argparse.Namespace,
) -> Dict[str, Any]:
    """Match one extracted new case against an immutable history snapshot."""

    configure_semantic_embedding_from_args(args)
    query_path = Path(query_path)
    query = load_case(query_path)
    query_resolved = query_path.resolve()
    loaded_candidates: List[Tuple[Path, CaseGraph]] = []
    seen_paths: Set[Path] = set()
    for source, graph in candidates:
        source_path = Path(source)
        resolved = source_path.resolve()
        # Re-running a batch after the new case was added must not match the
        # case against its own library copy.
        if (
            resolved == query_resolved
            or resolved in seen_paths
            or graph.case_id == query.case_id
        ):
            continue
        loaded_candidates.append((source_path, graph))
        seen_paths.add(resolved)

    if not loaded_candidates:
        raise ValueError(f"没有可用于匹配的历史案例: {query_path}")

    if _semantic_engine is not None:
        description_texts = []
        for graph in [query, *(graph for _, graph in loaded_candidates)]:
            for event_id in graph.event_ids:
                description = graph.nodes[event_id].description
                description_texts.append(description)
                description_texts.extend(description_units(description))
        _semantic_engine.preload(description_texts)
        if _reranker_engine is not None:
            recalled_pairs = []
            for query_event_id in query.event_ids:
                query_description = query.nodes[query_event_id].description
                for _, candidate_graph in loaded_candidates:
                    for candidate_event_id in candidate_graph.event_ids:
                        candidate_description = candidate_graph.nodes[
                            candidate_event_id
                        ].description
                        if (
                            text_similarity(query_description, candidate_description)
                            >= _embedding_recall_threshold
                        ):
                            recalled_pairs.extend(
                                (
                                    (query_description, candidate_description),
                                    (candidate_description, query_description),
                                )
                            )
            _reranker_engine.preload(recalled_pairs)

    # Every history case must enter GED. Vector retrieval is retained only to
    # calculate a diagnostic cosine score and no longer limits candidates.
    retrieved = faiss_event_type_search(
        query,
        loaded_candidates,
        len(loaded_candidates),
    )
    vector_scores = {
        path.resolve(): score for path, _, score in retrieved
    }

    all_results = []
    for path, candidate in loaded_candidates:
        vector_score = vector_scores.get(path.resolve(), 0.0)
        # The former hard prefilter is diagnostic only in all-pairs mode.
        filter_info = event_prefilter(
            query,
            candidate,
            min_shared_types=args.min_shared_event_types,
            min_overlap=args.min_event_overlap,
            min_count_ratio=args.min_event_count_ratio,
        )
        _, details = approximate_ged(query, candidate)
        all_results.append(
            {
                "case_id": candidate.case_id,
                "case_name": candidate.case_name,
                "source_file": str(path.resolve()),
                "vector_similarity": round(vector_score, 6),
                "shared_event_type_count": filter_info[
                    "shared_event_type_count"
                ],
                "event_type_overlap": filter_info["multiset_overlap"],
                "event_count_ratio": filter_info["event_count_ratio"],
                "ged": details["ged"],
                "normalization_upper_bound": details[
                    "normalization_upper_bound"
                ],
                "normalized_ged": details["normalized_ged"],
                "similarity": details["similarity"],
                "matched_subgraph": {
                    "matched_node_count": details["node_mapping_count"],
                    "matched_edge_count": details["matched_edge_count"],
                    "query_graph": details["query_graph"],
                    "candidate_graph": details["candidate_graph"],
                    "event_anchors": details["event_anchors"],
                    "matched_nodes": details["matched_nodes"],
                    "matched_edges": details["matched_edges"],
                    "query_full_graph": graph_payload(query),
                    "candidate_full_graph": graph_payload(candidate),
                },
                "event_similarity_summary": summarize_event_anchors(
                    details["event_anchors"]
                ),
            }
        )

    all_results.sort(
        key=lambda item: (item["normalized_ged"], item["case_id"])
    )
    anchored_results = [
        item
        for item in all_results
        if item["event_similarity_summary"]["anchor_count"] >= 1
    ]
    top_limit = min(TOP_RESULT_COUNT, len(all_results))
    top_results = [
        {
            "rank": index,
            **item,
        }
        for index, item in enumerate(all_results[:top_limit], start=1)
    ]
    return {
        "query_case": {"case_id": query.case_id, "case_name": query.case_name, "source_file": str(query_path.resolve())},
        "history_case_count": len(loaded_candidates),
        "history_source_mode": "case_library_snapshot",
        "matching_mode": "full_cartesian_all_history_cases",
        "pair_match_count": len(all_results),
        "output_format": "top_at_5_with_full_graphs_and_similarity_points",
        "top_k": TOP_RESULT_COUNT,
        "anchored_history_case_count": len(anchored_results),
        "similarity_metrics": {
            "primary_ranking_metric": "normalized_ged_ascending",
            "similarity_formula": "1 - normalized_ged",
            "normalization": "raw_ged / (full_query_deletion_cost + full_candidate_insertion_cost), clamped_to_0_1",
            "ged": "raw_approximate_graph_edit_distance",
            "vector_similarity": "event_type_count_vector_cosine_similarity",
            "event_type_overlap": "event_type_multiset_overlap",
            "event_count_ratio": "min_event_count / max_event_count",
            "event_description_similarity": "bge_small_recall_plus_reranker",
            "event_description_similarity_formula": (
                "embedding_cosine_recall_then_cross_encoder_reranker_score"
                if _reranker_engine is not None
                else "embedding_only_test_fallback"
            ),
            "embedding_recall_threshold": _embedding_recall_threshold,
            "semantic_embedding": {
                "enabled": _semantic_engine is not None,
                "model": (
                    _semantic_engine.model_name
                    if _semantic_engine is not None
                    else None
                ),
                "model_source": (
                    _semantic_engine.model_source
                    if _semantic_engine is not None
                    else None
                ),
                "device": (
                    _semantic_engine.device
                    if _semantic_engine is not None
                    else None
                ),
            },
            "reranker": {
                "enabled": _reranker_engine is not None,
                "model": (
                    _reranker_engine.model_name
                    if _reranker_engine is not None
                    else None
                ),
                "model_source": (
                    _reranker_engine.model_source
                    if _reranker_engine is not None
                    else None
                ),
                "device": (
                    _reranker_engine.device
                    if _reranker_engine is not None
                    else None
                ),
                "max_length": (
                    _reranker_engine.max_length
                    if _reranker_engine is not None
                    else None
                ),
            },
        },
        "match_summary": (
            f"新增案例已与全部 {len(all_results)} 个历史案例完成 GED 匹配；"
            f"输出相似度排名前 {top_limit} 个案例、相关指标及命中的节点与边。"
        ),
        "results": top_results,
    }


def discover_query_cases(query_root: Path) -> List[Path]:
    """Discover nested workflow outputs or a flat folder of case JSON files."""

    root = Path(query_root)
    if root.is_file():
        return [root]
    if not root.is_dir():
        raise NotADirectoryError(f"新增案例批次不存在: {root}")

    discovered = set(root.rglob("final_case.json"))
    for path in root.glob("*.json"):
        try:
            payload = json.loads(path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            continue
        if (
            isinstance(payload, dict)
            and isinstance(payload.get("basic_info"), dict)
            and isinstance(payload.get("events"), list)
            and isinstance(payload.get("relationships"), list)
        ):
            discovered.add(path)
    return sorted(discovered)


def _safe_file_component(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z._-]+", "_", value).strip("._") or "case"


def _write_json(path: Path, payload: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )




def _graph_checkpoint_payload(graph: CaseGraph) -> Dict[str, Any]:
    return {
        "case_id": graph.case_id,
        "nodes": [
            {
                "id": node_id,
                "kind": node.kind,
                "subtype": node.subtype,
                "label": node.label,
                "description": node.description,
                "attributes": node.attributes or {},
            }
            for node_id, node in sorted(graph.nodes.items())
        ],
        "edges": [
            {
                "left": edge.left,
                "right": edge.right,
                "relation": edge.relation,
                "description": edge.description,
            }
            for edge in sorted(
                graph.edges,
                key=lambda item: (
                    item.left,
                    item.right,
                    item.relation,
                    item.description,
                ),
            )
        ],
    }


def _similarity_input_fingerprint(
    query_path: Path,
    snapshot_candidates: Sequence[Tuple[Path, CaseGraph]],
    args: argparse.Namespace,
) -> str:
    payload = {
        "version": SIMILARITY_FINGERPRINT_VERSION,
        "query_sha256": hashlib.sha256(query_path.read_bytes()).hexdigest(),
        "history": [
            _graph_checkpoint_payload(graph)
            for _, graph in snapshot_candidates
        ],
        "settings": {
            "top_k": args.top_k,
            "retrieval_k": args.retrieval_k,
            "min_shared_event_types": args.min_shared_event_types,
            "min_event_overlap": args.min_event_overlap,
            "min_event_count_ratio": args.min_event_count_ratio,
            "rolling_library": args.rolling_library,
            "embedding_model": getattr(args, "embedding_model", None),
            "embedding_device": getattr(args, "embedding_device", None),
            "embedding_recall_threshold": getattr(
                args,
                "embedding_recall_threshold",
                EMBEDDING_RECALL_THRESHOLD,
            ),
            "reranker_model": getattr(args, "reranker_model", None),
            "reranker_device": getattr(args, "reranker_device", None),
            "reranker_max_length": getattr(args, "reranker_max_length", 512),
        },
    }
    return hashlib.sha256(
        json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def _load_reusable_similarity_result(
    previous: Dict[str, Any] | None,
    fingerprint: str,
) -> tuple[Dict[str, Any], Path] | None:
    if not previous or previous.get("status") not in {"success", "skipped"}:
        return None
    result_path_value = previous.get("result_path")
    if not result_path_value:
        return None
    result_path = Path(str(result_path_value))
    if not result_path.is_file():
        return None
    try:
        payload = json.loads(result_path.read_text(encoding="utf-8-sig"))
    except (json.JSONDecodeError, OSError):
        return None
    batch_processing = payload.get("batch_processing") or {}
    if batch_processing.get("input_fingerprint") != fingerprint:
        return None
    if not isinstance(payload.get("results"), list):
        return None
    return payload, result_path


def run_batch_queries(
    args: argparse.Namespace,
    library: CaseLibrary,
    history_candidates: Sequence[Tuple[Path, CaseGraph]],
) -> Dict[str, Any]:
    """Match every completed new case, then add it to the case library."""

    query_root = Path(args.query_root)
    output_dir = Path(args.batch_output_dir)
    query_paths = discover_query_cases(query_root)
    if not query_paths:
        raise ValueError(f"新增案例批次中没有 final_case.json: {query_root}")
    query_case_ids = {
        query_path: load_case(query_path).case_id for query_path in query_paths
    }
    output_dir.mkdir(parents=True, exist_ok=True)

    # Reuse the original history baseline when the same extraction batch is
    # matched again after more final_case.json files have appeared. This keeps
    # cases already added by an earlier partial match out of the candidate set.
    previous_summary_path = output_dir / "batch_similarity_summary.json"
    previous_summary: Dict[str, Any] = {}
    if previous_summary_path.is_file():
        try:
            loaded_summary = json.loads(
                previous_summary_path.read_text(encoding="utf-8")
            )
            if (
                isinstance(loaded_summary, dict)
                and loaded_summary.get("query_root") == str(query_root.resolve())
            ):
                previous_summary = loaded_summary
        except json.JSONDecodeError:
            previous_summary = {}

    saved_snapshot_ids = {
        str(case_id)
        for case_id in (previous_summary.get("history_snapshot_case_ids") or [])
    }
    if saved_snapshot_ids:
        snapshot_candidates = [
            (path, graph)
            for path, graph in history_candidates
            if graph.case_id in saved_snapshot_ids
        ]
        missing_ids = saved_snapshot_ids - {
            graph.case_id for _, graph in snapshot_candidates
        }
        if missing_ids:
            raise ValueError(
                "历史快照中的案例已从案例库删除: "
                + ", ".join(sorted(missing_ids))
            )
    else:
        # Compatibility for a partial result written before snapshot IDs were
        # introduced: remove its already-processed new case IDs from history.
        processed_query_ids = {
            str(item.get("case_id"))
            for item in (previous_summary.get("results") or [])
            if isinstance(item, dict) and item.get("case_id")
        }
        snapshot_candidates = [
            (path, graph)
            for path, graph in history_candidates
            if graph.case_id not in processed_query_ids
        ]
    if not snapshot_candidates:
        raise ValueError("历史案例快照为空，无法执行相似度匹配")

    rolling_candidates = list(snapshot_candidates)
    results: List[Dict[str, Any]] = []
    previous_results_by_input = {
        str(item.get("input_path")): item
        for item in (previous_summary.get("results") or [])
        if isinstance(item, dict) and item.get("input_path")
    }
    # Recover a completed match even if the process stopped after writing the
    # per-case JSON but before updating batch_similarity_summary.json. A
    # pending library update is safe: the resume branch performs the idempotent
    # add before moving to the next query.
    for saved_result_path in output_dir.glob("*_similarity.json"):
        try:
            saved_match = json.loads(
                saved_result_path.read_text(encoding="utf-8-sig")
            )
        except (json.JSONDecodeError, OSError):
            continue
        batch_processing = saved_match.get("batch_processing") or {}
        saved_input_path = str(batch_processing.get("input_path") or "")
        saved_fingerprint = str(
            batch_processing.get("input_fingerprint") or ""
        )
        if not saved_input_path or not saved_fingerprint:
            continue
        previous_results_by_input[saved_input_path] = {
            "input_path": saved_input_path,
            "status": "success",
            "match_status": "success",
            "result_path": str(saved_result_path.resolve()),
            "case_id": str(
                (saved_match.get("query_case") or {}).get("case_id") or ""
            ),
            "pair_match_count": int(saved_match.get("pair_match_count") or 0),
            "input_fingerprint": saved_fingerprint,
        }

    def build_summary(status: str) -> Dict[str, Any]:
        success_count = sum(
            item["status"] in {"success", "skipped"} for item in results
        )
        failure_count = sum(item["status"] == "failed" for item in results)
        skipped_count = sum(item["status"] == "skipped" for item in results)
        completed_pair_count = sum(
            int(item.get("pair_match_count") or 0) for item in results
        )
        return {
            "status": status,
            "processing_mode": "sequential_one_new_case_at_a_time",
            "case_processing_order": [
                "match_current_case_against_all_history_cases",
                "write_current_case_result",
                "add_current_case_to_library",
                "start_next_case",
            ],
            "query_root": str(query_root.resolve()),
            "output_dir": str(output_dir.resolve()),
            "case_library_root": str(library.root),
            "history_snapshot_case_count": len(snapshot_candidates),
            "history_snapshot_case_ids": [
                graph.case_id for _, graph in snapshot_candidates
            ],
            "query_case_count": len(query_paths),
            "expected_pair_count": sum(
                graph.case_id != query_case_ids[query_path]
                for query_path in query_paths
                for _, graph in snapshot_candidates
            ) + (
                sum(
                    query_case_ids[left_path] != query_case_ids[right_path]
                    for right_index, right_path in enumerate(query_paths)
                    for left_path in query_paths[:right_index]
                )
                if args.rolling_library
                else 0
            ),
            "completed_pair_count": completed_pair_count,
            "completed_count": len(results),
            "pending_count": len(query_paths) - len(results),
            "success_count": success_count,
            "failure_count": failure_count,
            "skipped_count": skipped_count,
            "add_to_library": not args.no_add_to_library,
            "rolling_library": args.rolling_library,
            "results": results,
        }

    _write_json(output_dir / "batch_similarity_summary.json", build_summary("running"))
    for index, query_path in enumerate(query_paths, start=1):
        item: Dict[str, Any] = {
            "sequence_index": index,
            "input_path": str(query_path.resolve()),
            "status": "failed",
            "match_status": "",
            "result_path": "",
            "case_id": "",
            "pair_match_count": 0,
            "library_status": "",
            "library_path": "",
            "error_type": "",
            "error": "",
            "input_fingerprint": "",
        }
        match_output: Dict[str, Any] | None = None
        result_path: Path | None = None
        try:
            candidates = (
                rolling_candidates if args.rolling_library else snapshot_candidates
            )
            fingerprint = _similarity_input_fingerprint(
                query_path,
                candidates,
                args,
            )
            item["input_fingerprint"] = fingerprint
            reusable = _load_reusable_similarity_result(
                previous_results_by_input.get(str(query_path.resolve())),
                fingerprint,
            )
            if reusable is not None:
                match_output, result_path = reusable
                match_output.pop("visualization_file", None)
                case_id = str(match_output["query_case"]["case_id"])
                item.update(
                    {
                        "case_id": case_id,
                        "result_path": str(result_path.resolve()),
                        "match_status": "skipped",
                        "pair_match_count": int(match_output["pair_match_count"]),
                    }
                )
                if not args.no_add_to_library:
                    library_result = library.add(
                        query_path,
                        on_conflict=args.library_on_conflict,
                    )
                    item["library_status"] = library_result.status
                    item["library_path"] = library_result.library_path
                    match_output["library_update"] = library_result.to_dict()
                else:
                    match_output["library_update"] = {"status": "disabled"}
                _write_json(result_path, match_output)
                item["status"] = "skipped"
                if args.rolling_library:
                    rolling_candidates.append((query_path, load_case(query_path)))
                results.append(item)
                _write_json(
                    output_dir / "batch_similarity_summary.json",
                    build_summary("running"),
                )
                continue

            match_output = match_query(query_path, candidates, args)
            case_id = str(match_output["query_case"]["case_id"])
            item["pair_match_count"] = int(match_output["pair_match_count"])
            result_path = (
                output_dir
                / f"{index:04d}_{_safe_file_component(case_id)}_similarity.json"
            )
            item["case_id"] = case_id
            item["result_path"] = str(result_path.resolve())
            item["match_status"] = "success"
            match_output["batch_processing"] = {
                "mode": "sequential_one_new_case_at_a_time",
                "sequence_index": index,
                "total_new_cases": len(query_paths),
                "input_path": str(query_path.resolve()),
                "input_fingerprint": fingerprint,
            }
            match_output["library_update"] = {
                "status": "pending" if not args.no_add_to_library else "disabled"
            }

            # Persist this case's complete all-history match before any library
            # mutation and before the next new case is allowed to start.
            _write_json(result_path, match_output)

            if not args.no_add_to_library:
                library_result = library.add(
                    query_path,
                    on_conflict=args.library_on_conflict,
                )
                item["library_status"] = library_result.status
                item["library_path"] = library_result.library_path
                match_output["library_update"] = library_result.to_dict()
                if args.rolling_library:
                    rolling_candidates.append((query_path, load_case(query_path)))

            # Record the final library outcome in the already-created result.
            _write_json(result_path, match_output)
            item["status"] = "success"
        except Exception as exc:
            if match_output is not None and result_path is not None:
                match_output["library_update"] = {
                    "status": "failed",
                    "error_type": type(exc).__name__,
                    "error": str(exc),
                }
                _write_json(result_path, match_output)
                if item["match_status"] == "success":
                    item["library_status"] = "failed"
            item["error_type"] = type(exc).__name__
            item["error"] = str(exc)
        results.append(item)
        _write_json(
            output_dir / "batch_similarity_summary.json",
            build_summary("running"),
        )

    summary = build_summary(
        "success"
        if all(item["status"] in {"success", "skipped"} for item in results)
        else "partial_failure"
    )
    _write_json(output_dir / "batch_similarity_summary.json", summary)
    return summary


def parse_args() -> argparse.Namespace:
    """Parse single-case or fixed-batch retrieval arguments."""

    parser = argparse.ArgumentParser(
        description="Retrieve similar cases with approximate undirected GED."
    )
    parser.add_argument("--query", help="单个新增案例 final_case.json；不传则运行批量模式")
    parser.add_argument(
        "--query-root",
        default=str(DEFAULT_QUERY_ROOT),
        help=f"新增案例批次目录，默认 {DEFAULT_QUERY_ROOT}",
    )
    parser.add_argument(
        "--history",
        default=str(DEFAULT_HISTORY_DIR),
        help=f"历史 final_case.json 目录，默认 {DEFAULT_HISTORY_DIR}",
    )
    parser.add_argument(
        "--library-root",
        default=str(DEFAULT_LIBRARY_ROOT),
        help=f"匹配后写入的案例库根目录，默认 {DEFAULT_LIBRARY_ROOT}",
    )
    parser.add_argument("--candidate", action="append", help="候选案例 JSON 文件，可重复指定")
    parser.add_argument("--case-dir", help="额外候选案例 JSON 目录")
    parser.add_argument(
        "--top-k",
        type=int,
        choices=(TOP_RESULT_COUNT,),
        default=TOP_RESULT_COUNT,
        help="固定输出 Top@5；该参数仅接受 5",
    )
    parser.add_argument("--retrieval-k", type=int, default=100, help="FAISS 初筛候选数量")
    parser.add_argument(
        "--embedding-model",
        default=DEFAULT_EMBEDDING_MODEL,
        help=f"中文事件描述向量模型，默认 {DEFAULT_EMBEDDING_MODEL}",
    )
    parser.add_argument(
        "--embedding-device",
        default="cpu",
        help="Embedding 推理设备，例如 cpu、cuda",
    )
    parser.add_argument(
        "--embedding-local-files-only",
        action="store_true",
        help="只从本地缓存加载 Embedding 模型，禁止联网下载",
    )
    parser.add_argument(
        "--embedding-recall-threshold",
        type=float,
        default=EMBEDDING_RECALL_THRESHOLD,
        help=f"进入Reranker的Embedding余弦阈值，默认 {EMBEDDING_RECALL_THRESHOLD}",
    )
    parser.add_argument(
        "--reranker-model",
        default=DEFAULT_RERANKER_MODEL,
        help=f"事件对精排模型，默认 {DEFAULT_RERANKER_MODEL}",
    )
    parser.add_argument(
        "--reranker-device",
        default="cpu",
        help="Reranker推理设备，例如 cpu、cuda",
    )
    parser.add_argument(
        "--reranker-local-files-only",
        action="store_true",
        help="只从本地缓存加载Reranker，禁止联网下载",
    )
    parser.add_argument(
        "--reranker-max-length",
        type=int,
        default=512,
        help="Reranker单个文本对最大Token长度，默认512",
    )
    parser.add_argument("--min-shared-event-types", type=int, default=MIN_SHARED_EVENT_TYPES, help="粗筛要求共享的最少事件类型数")
    parser.add_argument("--min-event-overlap", type=float, default=MIN_EVENT_OVERLAP, help="粗筛要求的事件类型多重集合重叠率")
    parser.add_argument("--min-event-count-ratio", type=float, default=MIN_EVENT_COUNT_RATIO, help="粗筛要求的案例事件数量比例")
    parser.add_argument("--output", help="单案例结果 JSON 输出路径；不指定时打印")
    parser.add_argument(
        "--batch-output-dir",
        default=str(DEFAULT_BATCH_OUTPUT_DIR),
        help=f"批量匹配结果目录，默认 {DEFAULT_BATCH_OUTPUT_DIR}",
    )
    parser.add_argument(
        "--no-add-to-library",
        action="store_true",
        help="只匹配，不在匹配完成后把新增案例写入案例库",
    )
    parser.add_argument(
        "--library-on-conflict",
        choices=("error", "skip", "replace"),
        default="error",
        help="案例库出现相同 case_id、不同内容时的处理方式",
    )
    parser.add_argument(
        "--rolling-library",
        action="store_true",
        help="后续新增案例也与本批次较早完成的新增案例匹配",
    )
    return parser.parse_args()


def _load_cli_candidates(args: argparse.Namespace) -> List[Tuple[Path, CaseGraph]]:
    history_cases = load_history_library(Path(args.history)) if args.history else []
    loaded = [(Path(source), graph) for source, graph in history_cases]
    if args.case_dir:
        loaded.extend(
            (path, load_case(path))
            for path in sorted(Path(args.case_dir).glob("*.json"))
        )
    loaded.extend(
        (Path(path), load_case(Path(path))) for path in (args.candidate or [])
    )
    return loaded


def main() -> int:
    """Run fixed-batch matching by default, or one-query matching on request."""

    args = parse_args()
    if args.retrieval_k < 1:
        raise SystemExit("--retrieval-k 必须大于0")
    history_candidates = _load_cli_candidates(args)
    if not history_candidates:
        raise SystemExit(f"历史案例库为空: {args.history}")
    library = CaseLibrary(Path(args.library_root))

    if args.query:
        query_path = Path(args.query)
        output = match_query(query_path, history_candidates, args)
        if not args.no_add_to_library:
            library_result = library.add(
                query_path,
                on_conflict=args.library_on_conflict,
            )
            output["library_update"] = library_result.to_dict()
        else:
            output["library_update"] = {"status": "disabled"}
        rendered = json.dumps(output, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            output_path = Path(args.output)
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(rendered, encoding="utf-8")
        else:
            print(rendered, end="")
        return 0

    summary = run_batch_queries(args, library, history_candidates)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 1 if summary["failure_count"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
