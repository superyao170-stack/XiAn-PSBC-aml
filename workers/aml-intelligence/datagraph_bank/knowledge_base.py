"""Knowledge-base storage and retrieval adapters.

The JSON knowledge base remains available as a migration input and a local
fallback. Qdrant becomes the runtime backend when KB_BACKEND=qdrant.
"""

from __future__ import annotations

import os
import re
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Optional, Sequence


DEFAULT_QDRANT_COLLECTION = "risk_event_knowledge_base"
DEFAULT_BGE_M3_MODEL = "BAAI/bge-m3"


class KnowledgeBaseUnavailable(RuntimeError):
    """Raised when the configured external knowledge base cannot be used."""


@dataclass(frozen=True)
class KnowledgeRecord:
    id: str
    name: str
    category: str
    rule: str
    version: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "category": self.category,
            "rule": self.rule,
        }


@dataclass(frozen=True)
class KnowledgeSearchHit:
    record: KnowledgeRecord
    score: float
    method: str


class JsonKnowledgeRepository:
    """Read-only compatibility repository for the existing JSON file."""

    backend = "json"
    supports_semantic_search = False

    def __init__(self, path: Path) -> None:
        import json

        self.path = Path(path).expanduser().resolve()
        if not self.path.is_file():
            raise FileNotFoundError(f"风险事件知识库不存在: {self.path}")
        try:
            value = json.loads(self.path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError as exc:
            raise KnowledgeBaseUnavailable(
                f"风险事件知识库不是有效JSON: {self.path}"
            ) from exc

        entries = value.get("event_types") if isinstance(value, dict) else None
        if not isinstance(entries, list):
            raise KnowledgeBaseUnavailable("知识库缺少 event_types 数组")

        metadata = value.get("metadata") if isinstance(value.get("metadata"), dict) else {}
        self.version = str(metadata.get("version") or "").strip() or None
        self.records = tuple(
            KnowledgeRecord(
                id=str(entry.get("id") or "").strip(),
                name=str(entry.get("name") or "").strip(),
                category=str(entry.get("category") or "").strip(),
                rule=str(entry.get("rule") or "").strip(),
                version=self.version,
            )
            for entry in entries
            if isinstance(entry, dict)
        )

    def list_records(self) -> tuple[KnowledgeRecord, ...]:
        return self.records


class BGEM3Embedder:
    """Lazy BGE-M3 encoder using FlagEmbedding when installed."""

    def __init__(
        self,
        model_name: str = DEFAULT_BGE_M3_MODEL,
        *,
        use_fp16: bool = False,
        batch_size: int = 8,
        max_length: int = 8192,
    ) -> None:
        self.model_name = model_name
        self.use_fp16 = use_fp16
        self.batch_size = batch_size
        self.max_length = max_length
        self._model: Any | None = None

    def _load_model(self) -> Any:
        if self._model is not None:
            return self._model
        try:
            from FlagEmbedding import BGEM3FlagModel
        except ImportError as exc:
            raise KnowledgeBaseUnavailable(
                "使用 BGE-M3 需要安装 FlagEmbedding 和 PyTorch"
            ) from exc
        try:
            self._model = BGEM3FlagModel(
                self.model_name,
                use_fp16=self.use_fp16,
            )
        except Exception as exc:  # pragma: no cover - depends on model runtime
            raise KnowledgeBaseUnavailable(
                f"BGE-M3 模型加载失败: {self.model_name}: {exc}"
            ) from exc
        return self._model

    def encode(self, texts: Sequence[str]) -> tuple[list[list[float]], list[dict[int, float]]]:
        if not texts:
            return [], []
        model = self._load_model()
        try:
            output = model.encode(
                list(texts),
                batch_size=self.batch_size,
                max_length=self.max_length,
            )
        except Exception as exc:  # pragma: no cover - depends on model runtime
            raise KnowledgeBaseUnavailable(f"BGE-M3 向量生成失败: {exc}") from exc

        dense_values = output.get("dense_vecs") if isinstance(output, dict) else None
        if dense_values is None:
            raise KnowledgeBaseUnavailable("BGE-M3 没有返回 dense_vecs")
        dense = [_to_float_list(value) for value in dense_values]

        sparse_values = output.get("lexical_weights") if isinstance(output, dict) else None
        sparse: list[dict[int, float]] = []
        for value in sparse_values or [{} for _ in texts]:
            if not isinstance(value, dict):
                sparse.append({})
                continue
            sparse.append(
                {
                    int(index): float(weight)
                    for index, weight in value.items()
                    if float(weight) != 0.0
                }
            )
        return dense, sparse


class QdrantKnowledgeRepository:
    """Qdrant-backed repository with dense and optional sparse BGE-M3 search."""

    backend = "qdrant"
    supports_semantic_search = True

    def __init__(
        self,
        *,
        url: str | None = None,
        path: Path | str | None = None,
        collection: str = DEFAULT_QDRANT_COLLECTION,
        model_name: str = DEFAULT_BGE_M3_MODEL,
        use_fp16: bool = False,
        client: Any | None = None,
        embedder: BGEM3Embedder | None = None,
        require_existing: bool = True,
    ) -> None:
        self.collection = collection
        self.model_name = model_name
        self._embedder = embedder
        self._client = client or self._build_client(url=url, path=path)
        self._version: str | None = None
        if require_existing and not self.collection_exists():
            raise KnowledgeBaseUnavailable(
                f"Qdrant collection 不存在: {self.collection}。"
                "请先运行 scripts/migrate_kb_to_qdrant.py"
            )

    @staticmethod
    def _build_client(*, url: str | None, path: Path | str | None) -> Any:
        try:
            from qdrant_client import QdrantClient
        except ImportError as exc:
            raise KnowledgeBaseUnavailable(
                "使用 Qdrant 需要安装 qdrant-client"
            ) from exc
        try:
            if path:
                return QdrantClient(path=str(Path(path).expanduser()))
            return QdrantClient(url=url or "http://localhost:6333")
        except Exception as exc:  # pragma: no cover - depends on service runtime
            raise KnowledgeBaseUnavailable(f"Qdrant 连接失败: {exc}") from exc

    @property
    def client(self) -> Any:
        return self._client

    @property
    def version(self) -> str | None:
        if self._version is not None:
            return self._version
        records = self.list_records(limit=1)
        self._version = records[0].version if records else None
        return self._version

    def _get_embedder(self) -> BGEM3Embedder:
        if self._embedder is None:
            self._embedder = BGEM3Embedder(self.model_name)
        return self._embedder

    def collection_exists(self) -> bool:
        try:
            method = getattr(self.client, "collection_exists", None)
            if callable(method):
                return bool(method(self.collection))
            self.client.get_collection(self.collection)
            return True
        except Exception:
            return False

    def list_records(self, limit: int = 1000) -> tuple[KnowledgeRecord, ...]:
        if not self.collection_exists():
            return ()
        records: list[KnowledgeRecord] = []
        offset: Any = None
        while len(records) < limit:
            points, next_offset = self.client.scroll(
                collection_name=self.collection,
                limit=min(256, limit - len(records)),
                offset=offset,
                with_payload=True,
                with_vectors=False,
            )
            for point in points:
                record = self._record_from_payload(getattr(point, "payload", None))
                if record is not None:
                    records.append(record)
            if next_offset is None or not points:
                break
            offset = next_offset
        return tuple(sorted(records, key=lambda item: item.id))

    def search(
        self,
        query: str,
        *,
        preferred_categories: Iterable[str] = (),
        top_k: int = 5,
    ) -> list[KnowledgeSearchHit]:
        if not self.collection_exists():
            return []
        embedder = self._get_embedder()
        dense_vectors, sparse_vectors = embedder.encode([query])
        dense_query = dense_vectors[0]
        sparse_query = sparse_vectors[0] if sparse_vectors else {}
        query_filter = self._category_filter(preferred_categories)
        points = self._hybrid_query(
            dense_query,
            sparse_query,
            query_filter=query_filter,
            top_k=max(1, min(top_k, 20)),
        )
        hits: list[KnowledgeSearchHit] = []
        for point in points:
            record = self._record_from_payload(getattr(point, "payload", None))
            if record is None:
                continue
            score = float(getattr(point, "score", 0.0) or 0.0)
            hits.append(
                KnowledgeSearchHit(
                    record=record,
                    score=score,
                    method="qdrant+bge-m3",
                )
            )
        return hits

    def ensure_collection(self, dense_size: int, *, with_sparse: bool = True) -> None:
        if self.collection_exists():
            return
        try:
            from qdrant_client import models

            sparse_config = None
            if with_sparse:
                sparse_config = {
                    "sparse": models.SparseVectorParams(
                        modifier=models.Modifier.IDF,
                    )
                }
            self.client.create_collection(
                collection_name=self.collection,
                vectors_config={
                    "dense": models.VectorParams(
                        size=dense_size,
                        distance=models.Distance.COSINE,
                    )
                },
                sparse_vectors_config=sparse_config,
            )
        except Exception as exc:  # pragma: no cover - depends on service runtime
            raise KnowledgeBaseUnavailable(
                f"Qdrant collection 创建失败: {self.collection}: {exc}"
            ) from exc

    def upsert_records(
        self,
        records: Sequence[KnowledgeRecord],
        *,
        batch_size: int = 8,
    ) -> int:
        if not records:
            return 0
        embedder = self._get_embedder()
        first_dense, _ = embedder.encode([_record_text(records[0])])
        self.ensure_collection(
            len(first_dense[0]),
            with_sparse=True,
        )
        from qdrant_client import models

        total = 0
        for start in range(0, len(records), batch_size):
            batch = list(records[start : start + batch_size])
            dense_vectors, sparse_vectors = embedder.encode(
                [_record_text(record) for record in batch]
            )
            points = []
            for index, record in enumerate(batch):
                vector: dict[str, Any] = {"dense": dense_vectors[index]}
                sparse = sparse_vectors[index] if index < len(sparse_vectors) else {}
                if sparse and self._collection_has_sparse():
                    vector["sparse"] = models.SparseVector(
                        indices=list(sparse),
                        values=[sparse[item] for item in sparse],
                    )
                payload = {
                    "event_id": record.id,
                    "name": record.name,
                    "category": record.category,
                    "rule": record.rule,
                    "kb_version": record.version,
                }
                points.append(
                    models.PointStruct(
                        id=str(uuid.uuid5(uuid.NAMESPACE_URL, f"risk-kb:{record.id}")),
                        vector=vector,
                        payload=payload,
                    )
                )
            self.client.upsert(
                collection_name=self.collection,
                points=points,
                wait=True,
            )
            total += len(points)
        self._version = records[0].version
        return total

    def _hybrid_query(
        self,
        dense_query: list[float],
        sparse_query: dict[int, float],
        *,
        query_filter: Any,
        top_k: int,
    ) -> list[Any]:
        try:
            from qdrant_client import models

            if sparse_query and self._collection_has_sparse():
                sparse_vector = models.SparseVector(
                    indices=list(sparse_query),
                    values=[sparse_query[item] for item in sparse_query],
                )
                prefetch = [
                    models.Prefetch(
                        query=dense_query,
                        using="dense",
                        limit=max(top_k * 2, 10),
                        filter=query_filter,
                    ),
                    models.Prefetch(
                        query=sparse_vector,
                        using="sparse",
                        limit=max(top_k * 2, 10),
                        filter=query_filter,
                    ),
                ]
                response = self.client.query_points(
                    collection_name=self.collection,
                    prefetch=prefetch,
                    query=models.FusionQuery(fusion=models.Fusion.RRF),
                    query_filter=query_filter,
                    limit=top_k,
                    with_payload=True,
                    with_vectors=False,
                )
                return list(getattr(response, "points", response))
        except Exception:
            pass

        try:
            response = self.client.query_points(
                collection_name=self.collection,
                query=dense_query,
                using="dense",
                query_filter=query_filter,
                limit=top_k,
                with_payload=True,
                with_vectors=False,
            )
            return list(getattr(response, "points", response))
        except (AttributeError, TypeError):
            response = self.client.search(
                collection_name=self.collection,
                query_vector=("dense", dense_query),
                query_filter=query_filter,
                limit=top_k,
                with_payload=True,
                with_vectors=False,
            )
            return list(response)

    def _category_filter(self, categories: Iterable[str]) -> Any:
        values = sorted({str(item).strip() for item in categories if str(item).strip()})
        if not values:
            return None
        try:
            from qdrant_client import models

            return models.Filter(
                should=[
                    models.FieldCondition(
                        key="category",
                        match=models.MatchValue(value=value),
                    )
                    for value in values
                ]
            )
        except ImportError as exc:
            raise KnowledgeBaseUnavailable(
                "使用 Qdrant 过滤需要安装 qdrant-client"
            ) from exc

    def _collection_has_sparse(self) -> bool:
        try:
            info = self.client.get_collection(self.collection)
            params = getattr(getattr(getattr(info, "config", None), "params", None), "sparse_vectors", None)
            return bool(params)
        except Exception:
            return False

    @staticmethod
    def _record_from_payload(payload: Any) -> KnowledgeRecord | None:
        if not isinstance(payload, dict):
            return None
        event_id = str(payload.get("event_id") or payload.get("id") or "").strip()
        if not event_id:
            return None
        return KnowledgeRecord(
            id=event_id,
            name=str(payload.get("name") or "").strip(),
            category=str(payload.get("category") or "").strip(),
            rule=str(payload.get("rule") or "").strip(),
            version=str(payload.get("kb_version") or "").strip() or None,
        )


def create_knowledge_repository(
    *,
    json_path: Path | None = None,
    backend: str | None = None,
    qdrant_url: str | None = None,
    qdrant_path: Path | str | None = None,
    qdrant_collection: str | None = None,
    bge_model: str | None = None,
) -> JsonKnowledgeRepository | QdrantKnowledgeRepository:
    selected = (backend or os.getenv("KB_BACKEND") or "auto").strip().lower()
    collection = (
        qdrant_collection
        or os.getenv("QDRANT_COLLECTION")
        or DEFAULT_QDRANT_COLLECTION
    )
    model_name = bge_model or os.getenv("BGE_M3_MODEL") or DEFAULT_BGE_M3_MODEL
    configured_qdrant = bool(
        qdrant_url
        or qdrant_path
        or os.getenv("QDRANT_URL")
        or os.getenv("QDRANT_PATH")
    )

    if selected == "json":
        if json_path is None:
            raise KnowledgeBaseUnavailable("JSON 后端需要提供 json_path")
        return JsonKnowledgeRepository(json_path)

    if selected == "qdrant":
        return QdrantKnowledgeRepository(
            url=qdrant_url or os.getenv("QDRANT_URL"),
            path=qdrant_path or os.getenv("QDRANT_PATH"),
            collection=collection,
            model_name=model_name,
        )

    if selected != "auto":
        raise ValueError("KB_BACKEND 只能是 auto、qdrant 或 json")

    if configured_qdrant:
        try:
            return QdrantKnowledgeRepository(
                url=qdrant_url or os.getenv("QDRANT_URL"),
                path=qdrant_path or os.getenv("QDRANT_PATH"),
                collection=collection,
                model_name=model_name,
            )
        except KnowledgeBaseUnavailable:
            pass

    if json_path is None:
        raise KnowledgeBaseUnavailable(
            "未配置可用的 Qdrant，且没有提供 JSON 回退路径"
        )
    return JsonKnowledgeRepository(json_path)


def _record_text(record: KnowledgeRecord) -> str:
    return f"{record.name}\n{record.category}\n{record.rule}"


def _to_float_list(value: Any) -> list[float]:
    if hasattr(value, "tolist"):
        value = value.tolist()
    return [float(item) for item in value]


def _tokens(text: str) -> set[str]:
    normalized = re.sub(r"\s+", "", text.lower())
    latin = set(re.findall(r"[a-z]+\d*|et\d+", normalized))
    chinese = re.findall(r"[\u4e00-\u9fff]+", normalized)
    bigrams = {
        word[index : index + 2]
        for word in chinese
        for index in range(max(0, len(word) - 1))
    }
    return latin | bigrams
