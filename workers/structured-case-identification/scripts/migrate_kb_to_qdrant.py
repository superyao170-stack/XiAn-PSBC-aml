#!/usr/bin/env python3
"""Build or refresh the BGE-M3 risk-event collection from governed JSON."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


WORKER_ROOT = Path(__file__).resolve().parents[1]
MODULE_ROOT = WORKER_ROOT / "xian_modules"
if str(MODULE_ROOT) not in sys.path:
    sys.path.insert(0, str(MODULE_ROOT))

from datagraph_bank.knowledge_base import (  # noqa: E402
    DEFAULT_BGE_M3_MODEL,
    DEFAULT_QDRANT_COLLECTION,
    JsonKnowledgeRepository,
    QdrantKnowledgeRepository,
)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="将风险事件 JSON 知识库以 BGE-M3 dense+sparse 向量写入 Qdrant",
    )
    parser.add_argument(
        "--kb",
        type=Path,
        default=MODULE_ROOT / "data" / "kb" / "risk_event_knowledge_base.json",
    )
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--url", help="Qdrant 服务地址，例如 http://127.0.0.1:6333")
    target.add_argument("--path", type=Path, help="本地 Qdrant 数据目录")
    parser.add_argument("--collection", default=DEFAULT_QDRANT_COLLECTION)
    parser.add_argument("--model", default=DEFAULT_BGE_M3_MODEL)
    parser.add_argument("--batch-size", type=int, default=8)
    args = parser.parse_args()

    source = JsonKnowledgeRepository(args.kb, model_name=args.model)
    destination = QdrantKnowledgeRepository(
        url=args.url,
        path=args.path,
        collection=args.collection,
        model_name=args.model,
        require_existing=False,
    )
    count = destination.upsert_records(
        source.list_records(),
        batch_size=max(1, args.batch_size),
    )
    print(
        f"已写入 {count} 条风险事件知识，collection={args.collection}，"
        f"model={args.model}，kb_version={source.version or 'unknown'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
