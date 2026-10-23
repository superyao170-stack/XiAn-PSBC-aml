#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import json
import threading
import time
from pathlib import Path

import requests


BASE = "http://127.0.0.1:3030/api/v1"
MANIFEST = Path(
    "/Users/zhangyao/Desktop/课题三/课题三-医疗腐败/输入数据/mock数据/"
    "case_llm_processing_manifest_20260923.json"
)

AML = [f"AML-N-{index:06d}" for index in range(2031, 2231)]
FRAUD = [f"FRD-N-{index:06d}" for index in range(1004, 1104)]

TARGETS = {
    "report": ("REPORT", "PENDING_REPORT", AML[:150] + FRAUD[:75]),
    "framework": ("FRAMEWORK", "PENDING_EXTRACTION", AML[:100] + FRAUD[:50]),
    "similarity": ("SIMILARITY", "PENDING_SIMILARITY", AML[:50] + FRAUD[:25]),
}

_local = threading.local()


def session() -> requests.Session:
    current = getattr(_local, "session", None)
    if current is not None:
        return current
    current = requests.Session()
    response = current.post(
        f"{BASE}/auth/login",
        json={"username": "sadmin", "password": "sadmin@123"},
        timeout=30,
    )
    response.raise_for_status()
    body = response.json()
    token = (body.get("data") or {}).get("token")
    if not token:
        raise RuntimeError(f"Login failed: {body}")
    current.headers["Authorization"] = f"Bearer {token}"
    _local.session = current
    return current


def detail(case_id: str) -> dict:
    response = session().get(f"{BASE}/case-processing/{case_id}", timeout=60)
    response.raise_for_status()
    body = response.json()
    if body.get("code") != 200:
        raise RuntimeError(str(body))
    return body["data"]


def invoke(case_id: str, action: str, required_stage: str) -> dict:
    before = detail(case_id).get("processingStage")
    if before != required_stage:
        return {
            "caseId": case_id,
            "status": "SKIPPED",
            "before": before,
            "reason": f"expected {required_stage}",
        }
    last_error = ""
    for attempt in range(1, 4):
        try:
            response = session().post(
                f"{BASE}/case-processing/actions/{action}",
                json={"caseIds": [case_id]},
                timeout=7200,
            )
            response.raise_for_status()
            body = response.json()
            result = ((body.get("data") or {}).get("results") or [{}])[0]
            if body.get("code") == 200 and result.get("status") == "SUCCEEDED":
                return {
                    "caseId": case_id,
                    "status": "SUCCEEDED",
                    "before": before,
                    "after": result.get("processingStage"),
                    "attempt": attempt,
                }
            last_error = str(result.get("error") or body.get("message") or body)[:1000]
        except Exception as exc:
            last_error = f"{type(exc).__name__}: {exc}"[:1000]
        if attempt < 3:
            time.sleep(2 * attempt)
    return {
        "caseId": case_id,
        "status": "FAILED",
        "before": before,
        "error": last_error,
    }


def save_manifest(document: dict) -> None:
    MANIFEST.write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding="utf-8")


def run_phase(name: str, workers: int, manifest: dict) -> bool:
    action, required_stage, case_ids = TARGETS[name]
    started = time.time()
    results: list[dict] = []
    print(
        json.dumps(
            {"phase": name, "action": action, "total": len(case_ids), "workers": workers},
            ensure_ascii=False,
        ),
        flush=True,
    )
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {
            pool.submit(invoke, case_id, action, required_stage): case_id
            for case_id in case_ids
        }
        for future in concurrent.futures.as_completed(futures):
            case_id = futures[future]
            try:
                results.append(future.result())
            except Exception as exc:
                results.append(
                    {"caseId": case_id, "status": "FAILED", "error": str(exc)[:1000]}
                )
            completed = len(results)
            if completed % 5 == 0 or completed == len(case_ids):
                succeeded = sum(item["status"] == "SUCCEEDED" for item in results)
                skipped = sum(item["status"] == "SKIPPED" for item in results)
                failed = completed - succeeded - skipped
                print(
                    json.dumps(
                        {
                            "phase": name,
                            "completed": completed,
                            "total": len(case_ids),
                            "succeeded": succeeded,
                            "skipped": skipped,
                            "failed": failed,
                            "elapsedSeconds": round(time.time() - started, 1),
                        },
                        ensure_ascii=False,
                    ),
                    flush=True,
                )
                manifest.setdefault("phases", {})[name] = {
                    "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                    "results": sorted(results, key=lambda item: item["caseId"]),
                }
                save_manifest(manifest)
    failed_items = [item for item in results if item["status"] == "FAILED"]
    manifest["phases"][name]["completedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    manifest["phases"][name]["summary"] = {
        "requested": len(case_ids),
        "succeeded": sum(item["status"] == "SUCCEEDED" for item in results),
        "skipped": sum(item["status"] == "SKIPPED" for item in results),
        "failed": len(failed_items),
        "elapsedSeconds": round(time.time() - started, 1),
    }
    save_manifest(manifest)
    if failed_items:
        print(json.dumps({"phase": name, "failedItems": failed_items}, ensure_ascii=False), flush=True)
    return not failed_items


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--phase", choices=["all", *TARGETS], default="all", help="Processing phase"
    )
    parser.add_argument("--report-workers", type=int, default=4)
    parser.add_argument("--framework-workers", type=int, default=6)
    parser.add_argument("--similarity-workers", type=int, default=6)
    args = parser.parse_args()

    manifest = {
        "startedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "modelWorkflow": "system REPORT action using configured DeepSeek provider",
        "targets": {
            "reports": {"AML": 150, "ANTI_FRAUD": 75},
            "framework": {"AML": 100, "ANTI_FRAUD": 50},
            "similarity": {"AML": 50, "ANTI_FRAUD": 25},
        },
        "phases": {},
    }
    phases = list(TARGETS) if args.phase == "all" else [args.phase]
    worker_counts = {
        "report": max(1, args.report_workers),
        "framework": max(1, args.framework_workers),
        "similarity": max(1, args.similarity_workers),
    }
    for phase in phases:
        if not run_phase(phase, worker_counts[phase], manifest):
            manifest["status"] = "FAILED"
            manifest["failedPhase"] = phase
            save_manifest(manifest)
            raise SystemExit(1)
    manifest["status"] = "SUCCEEDED"
    manifest["completedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    save_manifest(manifest)
    print(json.dumps({"status": "SUCCEEDED", "manifest": str(MANIFEST)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
