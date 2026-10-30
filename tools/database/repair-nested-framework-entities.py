#!/usr/bin/env python3
"""Restore nested entity collections without rerunning events or changing approval.

Run with workers/.venv/bin/python. A job ID is required so old benchmark jobs
are never modified accidentally. Defaults to a dry run; --apply writes a private
backup before updating the processing pool and case library in one transaction.
After applying, rebuild each case through POST /api/v1/graph/case-rebuild.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "workers/structured-case-identification"))
from framework_extraction import graph_snapshot
from datagraph_bank.input_normalization import normalize_case_entities
from datagraph_bank.models import AccountEntity, Evidence, OtherEntity, Relationship
from datagraph_bank.nodes.relationships import RelationshipExtractor


def repair_framework(document: dict, source: dict) -> dict:
    result = copy.deepcopy(document)
    candidate = copy.deepcopy(document)
    normalized_source = normalize_case_entities(source)
    # Retain extracted values; use source collections only as missing input.
    basic = candidate.setdefault("basic_info", {})
    for field in ("accounts", "other_entities", "evidences"):
        if not basic.get(field):
            basic[field] = normalized_source[field]
    normalized = normalize_case_entities(candidate)
    for field, model in (("accounts", AccountEntity), ("other_entities", OtherEntity),
                         ("evidences", Evidence)):
        result[field] = [{**item, **model.model_validate(item).model_dump(mode="json")}
                         for item in normalized[field]]

    extractor = RelationshipExtractor()
    nodes = extractor._build_node_registry(result)
    candidates = extractor._structural_relationships(result, nodes)
    candidates += extractor._local_entity_entity_relationships(result)
    structural, _ = extractor._deduplicate(candidates, nodes)
    relationships = result.setdefault("relationships", [])
    key = lambda item: tuple(item.get(name) for name in
                            ("relationship_type", "source_node_id", "target_node_id"))
    existing = {key(item) for item in relationships}
    for relation in structural:
        identity = key(relation)
        if identity in existing:
            continue
        digest = hashlib.sha256(json.dumps(identity, ensure_ascii=False).encode()).hexdigest()[:20]
        relation["relationship_id"] = "REL-RESTORED-" + digest
        relationships.append(Relationship.model_validate(relation).model_dump(mode="json"))
        existing.add(identity)
    return result


def literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--job-id", required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--container", default="bankgraph-postgres")
    parser.add_argument("--database", default="databank")
    parser.add_argument("--user", default="postgres")
    args = parser.parse_args()
    command = ["docker", "exec", "-i", args.container, "psql", "-X", "-qAt",
               "-v", "ON_ERROR_STOP=1", "-U", args.user, "-d", args.database]

    def sql(statement: str) -> str:
        return subprocess.run(command, input=statement, text=True, check=True,
                              capture_output=True).stdout.strip()

    rows = [json.loads(line) for line in sql("""
      SELECT json_build_object('case_id',p.case_id,'source_payload',p.source_payload,
        'framework_result',p.framework_result,'graph_snapshot',p.graph_snapshot,
        'processing_stage',p.processing_stage,'case_status',c.case_status,'bank_code',c.bank_code,
        'case_snapshot_id',c.graph_snapshot_id,'case_snapshot_sha256',c.graph_snapshot_sha256,
        'source_md5',md5(p.source_payload::text),'framework_md5',md5(p.framework_result::text),
        'library_document',l.case_document,'library_md5',md5(l.case_document::text),
        'library_status',l.status,'library_sha256',l.content_sha256)
      FROM case_processing_pool p JOIN cf_risk_case c ON c.case_id=p.case_id
      LEFT JOIN structured_case_library l ON l.case_id=p.case_id
      WHERE c.deleted=false AND p.scenario_code IN ('ANTI_MONEY_LAUNDERING','AML')
        AND p.framework_result IS NOT NULL AND p.job_id=
    """ + literal(args.job_id) + " ORDER BY p.case_id;").splitlines() if line]
    updates = []
    for row in rows:
        repaired = repair_framework(row["framework_result"], row["source_payload"])
        library = (repair_framework(row["library_document"], row["source_payload"])
                   if row["library_document"] else None)
        snapshot = graph_snapshot(repaired)
        snapshot_json = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        snapshot_hash = hashlib.sha256(snapshot_json.encode()).hexdigest()
        snapshot_id = "GS-XI-" + hashlib.sha256(
            (row["case_id"] + "\0" + snapshot_hash).encode()).hexdigest()[:40].upper()
        if (repaired == row["framework_result"] and library == row["library_document"]
                and row["graph_snapshot"] == snapshot
                and row["case_snapshot_id"] == snapshot_id
                and row["case_snapshot_sha256"] == snapshot_hash):
            continue
        updates.append((row, repaired, library, snapshot, snapshot_hash, snapshot_id, snapshot_json))
        print(json.dumps({"caseId": row["case_id"], "accounts": len(repaired["accounts"]),
                          "otherEntities": len(repaired["other_entities"]),
                          "evidences": len(repaired["evidences"]),
                          "eventsUnchanged": repaired.get("events") == row["framework_result"].get("events")},
                         ensure_ascii=False))
    print(f"Cases requiring repair: {len(updates)}; apply={args.apply}")
    if not args.apply or not updates:
        return

    backup_dir = ROOT / "artifacts/backups"
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup = backup_dir / ("nested-entities-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".json")
    descriptor = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as handle:
        json.dump(rows, handle, ensure_ascii=False, indent=2)
    statements = ["BEGIN; SET LOCAL standard_conforming_strings=on;"]
    encode = lambda value: literal(json.dumps(value, ensure_ascii=False)) + "::jsonb"
    for row, framework, library, snapshot, snapshot_hash, snapshot_id, snapshot_json in updates:
        case = literal(row["case_id"])
        # Refuse concurrent framework/source/library edits rather than overwriting.
        statements.append(f"""DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM case_processing_pool WHERE case_id={case}
             AND md5(source_payload::text)={literal(row['source_md5'])}
             AND md5(framework_result::text)={literal(row['framework_md5'])}
             AND graph_snapshot IS NOT DISTINCT FROM {encode(row['graph_snapshot'])} FOR UPDATE)
          THEN RAISE EXCEPTION 'Case changed during repair'; END IF;
        END $$;""")
        statements.append(f"""UPDATE case_processing_pool SET
          source_payload={encode(normalize_case_entities(row['source_payload']))},
          framework_result={encode(framework)},graph_snapshot={encode(snapshot)},
          updated_at=CURRENT_TIMESTAMP WHERE case_id={case};""")
        statements.append(f"""INSERT INTO graph_snapshot
          (snapshot_id,bank_code,case_id,engine_type,snapshot_sha256,node_count,edge_count,
           snapshot_size,metadata,status,completed_at)
          VALUES ({literal(snapshot_id)},{literal(row['bank_code'])},{case},'WORKER_JSON',
            {literal(snapshot_hash)},{snapshot['nodeCount']},{snapshot['edgeCount']},
            {len(snapshot_json.encode())},{encode(snapshot)},'COMPLETED',CURRENT_TIMESTAMP)
          ON CONFLICT (snapshot_id) DO NOTHING;
          UPDATE cf_risk_case SET graph_snapshot_id={literal(snapshot_id)},
            graph_snapshot_sha256={literal(snapshot_hash)},updated_at=CURRENT_TIMESTAMP
          WHERE case_id={case};""")
        if library is not None:
            statements.append(f"""DO $$ BEGIN
              IF NOT EXISTS (SELECT 1 FROM structured_case_library WHERE case_id={case}
                 AND md5(case_document::text)={literal(row['library_md5'])} FOR UPDATE)
              THEN RAISE EXCEPTION 'Library changed during repair'; END IF;
            END $$;""")
            digest = hashlib.sha256(json.dumps(library, ensure_ascii=False).encode()).hexdigest()
            statements.append(f"""UPDATE structured_case_library SET case_document={encode(library)},
              content_sha256={literal(digest)},updated_at=CURRENT_TIMESTAMP WHERE case_id={case};""")
    statements.append("COMMIT;")
    sql("\n".join(statements))
    print(f"Saved backup: {backup}")
    print("Restored entity collections and graph snapshots; rebuild TuGraph for the listed cases.")


if __name__ == "__main__":
    main()
