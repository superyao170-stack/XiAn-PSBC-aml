"""File-backed storage for extracted ``final_case.json`` documents."""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
import threading
from contextlib import contextmanager
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, Literal

try:  # pragma: no cover - available on the supported Linux/macOS targets
    import fcntl
except ImportError:  # pragma: no cover
    fcntl = None


MANIFEST_FILE_NAME = "case_library_manifest.json"
SCHEMA_VERSION = 1
ConflictMode = Literal["error", "skip", "replace"]


class CaseLibraryError(RuntimeError):
    """Base error for case-library operations."""


class InvalidFinalCaseError(CaseLibraryError):
    """Raised when a document is not a usable extracted final case."""


class CaseLibraryConflictError(CaseLibraryError):
    """Raised when a different document already owns the same case ID."""


@dataclass(frozen=True)
class CaseLibraryWriteResult:
    case_id: str
    case_name: str
    status: str
    library_path: str
    content_sha256: str
    source_path: str

    def to_dict(self) -> Dict[str, str]:
        return asdict(self)


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _canonical_json_bytes(payload: Dict[str, Any]) -> bytes:
    return json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _content_sha256(payload: Dict[str, Any]) -> str:
    return hashlib.sha256(_canonical_json_bytes(payload)).hexdigest()


def _atomic_write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temp_name = tempfile.mkstemp(
        dir=str(path.parent),
        prefix=f".{path.name}.",
        suffix=".tmp",
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as file:
            json.dump(payload, file, ensure_ascii=False, indent=2)
            file.write("\n")
            file.flush()
            os.fsync(file.fileno())
        os.replace(temp_name, path)
    except Exception:
        try:
            os.unlink(temp_name)
        except FileNotFoundError:
            pass
        raise


def load_final_case(path: Path) -> Dict[str, Any]:
    """Load and minimally validate one extracted ``final_case.json``."""

    source = Path(path)
    if not source.is_file():
        raise FileNotFoundError(f"final_case.json 不存在: {source}")
    try:
        payload = json.loads(source.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        raise InvalidFinalCaseError(
            f"final_case.json 格式错误: {source}（第 {exc.lineno} 行，第 {exc.colno} 列）"
        ) from exc
    validate_final_case(payload, source)
    return payload


def validate_final_case(payload: Any, source: Path | str = "<memory>") -> None:
    """Validate the fields required by storage and graph matching."""

    if not isinstance(payload, dict):
        raise InvalidFinalCaseError(f"final_case 顶层必须是 JSON 对象: {source}")
    basic_info = payload.get("basic_info")
    if not isinstance(basic_info, dict):
        raise InvalidFinalCaseError(f"final_case.basic_info 必须是 JSON 对象: {source}")
    if not str(basic_info.get("case_id") or "").strip():
        raise InvalidFinalCaseError(f"final_case.basic_info.case_id 不能为空: {source}")
    for field_name in (
        "customers",
        "accounts",
        "other_entities",
        "events",
        "relationships",
        "evidences",
    ):
        if not isinstance(payload.get(field_name), list):
            raise InvalidFinalCaseError(
                f"final_case.{field_name} 必须是 JSON 数组: {source}"
            )


def discover_final_case_files(input_root: Path) -> list[Path]:
    """Discover extracted cases in a workflow batch or a flat upload directory."""

    root = Path(input_root)
    if root.is_file():
        return [root]
    if not root.is_dir():
        raise NotADirectoryError(f"案例导入目录不存在: {root}")

    workflow_outputs = sorted(root.rglob("final_case.json"))
    if workflow_outputs:
        return workflow_outputs

    return sorted(
        path
        for path in root.rglob("*.json")
        if path.name != MANIFEST_FILE_NAME
    )


class CaseLibrary:
    """Store one current extracted document per stable ``case_id``."""

    def __init__(self, root: Path) -> None:
        self.root = Path(root).expanduser().absolute()
        self.cases_dir = self.root / "cases"
        self.manifest_path = self.root / MANIFEST_FILE_NAME
        self.lock_path = self.root / ".case_library.lock"
        self._thread_lock = threading.RLock()

    @contextmanager
    def _locked(self) -> Iterator[None]:
        with self._thread_lock:
            self.root.mkdir(parents=True, exist_ok=True)
            with self.lock_path.open("a+", encoding="utf-8") as lock_file:
                if fcntl is not None:
                    fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    if fcntl is not None:
                        fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)

    def _empty_manifest(self) -> Dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "updated_at": "",
            "case_count": 0,
            "cases": [],
        }

    def _load_manifest_unlocked(self) -> Dict[str, Any]:
        if not self.manifest_path.exists():
            return self._empty_manifest()
        try:
            manifest = json.loads(self.manifest_path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError as exc:
            raise CaseLibraryError(
                f"案例库清单格式错误: {self.manifest_path}"
            ) from exc
        if not isinstance(manifest, dict) or not isinstance(manifest.get("cases"), list):
            raise CaseLibraryError(f"案例库清单结构无效: {self.manifest_path}")
        if manifest.get("schema_version") != SCHEMA_VERSION:
            raise CaseLibraryError(
                f"不支持的案例库版本: {manifest.get('schema_version')}"
            )
        return manifest

    def manifest(self) -> Dict[str, Any]:
        with self._locked():
            return self._load_manifest_unlocked()

    def case_paths(self) -> list[Path]:
        """Return current case files in deterministic case-ID order."""

        manifest = self.manifest()
        paths = []
        for record in sorted(manifest["cases"], key=lambda item: item["case_id"]):
            path = self.root / str(record["relative_path"])
            if not path.is_file():
                raise CaseLibraryError(f"案例库文件缺失: {path}")
            paths.append(path)
        return paths

    def add(self, source_path: Path, on_conflict: ConflictMode = "error") -> CaseLibraryWriteResult:
        """Copy one extracted case into the library with atomic manifest updates."""

        if on_conflict not in {"error", "skip", "replace"}:
            raise ValueError(f"不支持的冲突策略: {on_conflict}")
        source = Path(source_path).expanduser().absolute()
        payload = load_final_case(source)
        basic_info = payload["basic_info"]
        case_id = str(basic_info["case_id"]).strip()
        case_name = str(basic_info.get("case_name") or "").strip()
        content_sha256 = _content_sha256(payload)
        safe_case_id = (
            re.sub(r"[^0-9A-Za-z._-]+", "_", case_id).strip("._")[:80]
            or "case"
        )
        id_hash = hashlib.sha256(case_id.encode("utf-8")).hexdigest()[:12]
        destination = self.cases_dir / f"{safe_case_id}_{id_hash}.json"

        with self._locked():
            manifest = self._load_manifest_unlocked()
            records = manifest["cases"]
            existing = next(
                (item for item in records if str(item.get("case_id")) == case_id),
                None,
            )
            if existing and existing.get("content_sha256") == content_sha256:
                return CaseLibraryWriteResult(
                    case_id=case_id,
                    case_name=case_name,
                    status="unchanged",
                    library_path=str((self.root / existing["relative_path"]).resolve()),
                    content_sha256=content_sha256,
                    source_path=str(source),
                )
            if existing and on_conflict == "error":
                raise CaseLibraryConflictError(
                    f"案例库已存在不同内容的 case_id={case_id}；"
                    "请使用 skip 或 replace 冲突策略"
                )
            if existing and on_conflict == "skip":
                return CaseLibraryWriteResult(
                    case_id=case_id,
                    case_name=str(existing.get("case_name") or case_name),
                    status="skipped",
                    library_path=str((self.root / existing["relative_path"]).resolve()),
                    content_sha256=str(existing.get("content_sha256") or ""),
                    source_path=str(source),
                )

            now = _utc_now()
            _atomic_write_json(destination, payload)
            relative_path = str(destination.relative_to(self.root))
            record = {
                "case_id": case_id,
                "case_name": case_name,
                "relative_path": relative_path,
                "content_sha256": content_sha256,
                "source_path": str(source),
                "added_at": existing.get("added_at", now) if existing else now,
                "updated_at": now,
            }
            if existing:
                records[records.index(existing)] = record
                status = "replaced"
            else:
                records.append(record)
                status = "added"
            records.sort(key=lambda item: item["case_id"])
            manifest["updated_at"] = now
            manifest["case_count"] = len(records)
            _atomic_write_json(self.manifest_path, manifest)

        return CaseLibraryWriteResult(
            case_id=case_id,
            case_name=case_name,
            status=status,
            library_path=str(destination.resolve()),
            content_sha256=content_sha256,
            source_path=str(source),
        )

    def import_paths(
        self,
        paths: Iterable[Path],
        on_conflict: ConflictMode = "error",
    ) -> Dict[str, Any]:
        """Import many cases while isolating invalid files and conflicts."""

        results = []
        for path in paths:
            try:
                result = self.add(path, on_conflict=on_conflict)
                results.append(result.to_dict())
            except Exception as exc:
                results.append(
                    {
                        "source_path": str(Path(path).expanduser().absolute()),
                        "status": "failed",
                        "error_type": type(exc).__name__,
                        "error": str(exc),
                    }
                )
        return {
            "library_root": str(self.root),
            "input_count": len(results),
            "success_count": sum(item["status"] != "failed" for item in results),
            "failure_count": sum(item["status"] == "failed" for item in results),
            "added_count": sum(item["status"] == "added" for item in results),
            "replaced_count": sum(item["status"] == "replaced" for item in results),
            "unchanged_count": sum(item["status"] == "unchanged" for item in results),
            "skipped_count": sum(item["status"] == "skipped" for item in results),
            "results": results,
        }
