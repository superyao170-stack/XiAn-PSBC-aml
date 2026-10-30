#!/usr/bin/env python3
"""Offline detector and validation/test benchmark. Uses only Python stdlib."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "workers/risk-analytics-service"))
from collaborative_aml import detect_collaborative_aml, sha
from aml_metrics import classification_metrics


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n")


def fixture(seed, isolated=1500):
    """Known labels separate from graph; hard legitimate scatter-gather negatives.

    Each generated group is independent. Small/deep illicit chains test exclusions.
    Labels reflect generator intent and cannot prove business discrimination.
    """
    rng = random.Random(seed)
    accounts, transactions, labels, strata = [], [], {}, {}

    def account(key, bank, illicit, stratum):
        accounts.append({"id": key, "institution": bank})
        labels[key], strata[key] = int(illicit), stratum

    def tx(source, target):
        tid = f"TX-{seed}-{len(transactions):05d}"
        transactions.append({"transactionId": tid, "source": source, "target": target,
                             "amount": rng.randint(1000, 20000), "currency": "CNY",
                             "occurredAt": "2026-09-01T08:00:00+08:00", "evidenceRef": tid})

    def group(number, width, illicit, kind):
        prefix = f"D{seed}-G{number}"
        source, target = prefix + "-S", prefix + "-D"
        account(source, "A", illicit, kind)
        account(target, "B", illicit, kind)
        for i in range(width):
            left, right = f"{prefix}-L{i}", f"{prefix}-R{i}"
            account(left, "A", illicit, kind)
            account(right, "B", illicit, kind)
            tx(source, left)
            tx(left, right)
            if kind == "ILLICIT_DEEP":
                middle = f"{prefix}-M{i}"
                account(middle, "B", illicit, kind)
                tx(right, middle)
                tx(middle, target)
            else:
                tx(right, target)

    number = 0
    for kind, count, low, high, illicit in [
        ("ILLICIT_SCATTER_GATHER", 6, 5, 10, True),
        ("ILLICIT_SMALL", 2, 2, 3, True),
        ("ILLICIT_DEEP", 2, 5, 7, True),
        ("LEGITIMATE_COLLECTION", 6, 5, 10, False),
    ]:
        for _ in range(count):
            group(number, rng.randint(low, high), illicit, kind)
            number += 1
    # Independent fan-out / fan-in normal traffic lacks corresponding endpoint sets.
    for number in range(12):
        hub = f"D{seed}-N{number}-H"
        bank = "A" if number % 2 == 0 else "B"
        account(hub, bank, False, "NORMAL_ONE_SIDED")
        for i in range(rng.randint(5, 10)):
            leaf = f"D{seed}-N{number}-L{i}"
            account(leaf, "B" if bank == "A" else "A", False, "NORMAL_ONE_SIDED")
            tx(hub, leaf) if number % 2 == 0 else tx(leaf, hub)
    for i in range(isolated):
        account(f"D{seed}-I{i}", "A" if i % 2 == 0 else "B", False, "NORMAL_ISOLATED")
    # Shuffle verifies that the detector does not rely on contiguous account IDs or order.
    rng.shuffle(accounts)
    rng.shuffle(transactions)
    return {"accounts": accounts, "transactions": transactions}, labels, strata


def rows_for(result, labels, strata):
    evidence = {x["subjectId"]: x for x in result["evidence"]}
    if set(evidence) != set(labels): raise ValueError("Evaluation account universe differs from labels")
    return [{"accountId": key, "actual": labels[key],
             "predicted": evidence[key]["decision"] == "SUSPECTED",
             "score": evidence[key]["score"], "stratum": strata.get(key, "EXTERNAL")}
            for key in sorted(labels)]


def detect_file(args):
    payload = json.loads(args.input.read_text())
    result = detect_collaborative_aml(payload)
    save(args.output, result)
    if args.labels:
        label_data = json.loads(args.labels.read_text())
        rows = rows_for(result, label_data, {})
        save(args.output.with_suffix(".metrics.json"), classification_metrics(rows))
    print(json.dumps(result["summary"], ensure_ascii=False))


def benchmark(args):
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    defaults = {"rows": 2, "bands": 50, "maxDepth": 2, "minSetSize": 5,
                "bloomBits": 500000, "bloomHashes": 7, "seed": 42, "minAmount": 0}
    validation_graph, validation_labels, validation_strata = fixture(10001)
    save(out / "validation_graph.json", validation_graph)
    save(out / "validation_labels.json", validation_labels)
    validation = []
    for threshold in [.2, .3, .4, .5, .6, .7, .8]:
        result = detect_collaborative_aml({**validation_graph, **defaults, "threshold": threshold})
        metrics = classification_metrics(rows_for(result, validation_labels, validation_strata))
        validation.append({"threshold": threshold, "metrics": metrics})
    chosen = max(validation, key=lambda x: (x["metrics"]["f1"], x["metrics"]["precision"], -x["threshold"]))
    config = {**defaults, "threshold": chosen["threshold"]}
    # Write the freeze before loading/generating test labels.
    freeze = {"parameters": config, "selection": "MAX_VALIDATION_F1_THEN_PRECISION_THEN_LOWER_THRESHOLD",
              "validationGraphSha256": sha(validation_graph), "validationLabelsSha256": sha(validation_labels),
              "validation": validation, "testLabelsUsedForSelection": False}
    save(out / "frozen_parameters.json", freeze)
    runs, dataset_manifest = [], []
    for scenario, dataset_seed, isolated in [("CONTROLLED", 20001, 1500), ("SPARSER_POSITIVES", 30001, 5000)]:
        graph, labels, strata = fixture(dataset_seed, isolated)
        save(out / f"{scenario.lower()}_graph.json", graph)
        save(out / f"{scenario.lower()}_labels.json", labels)
        dataset_manifest.append({"scenario": scenario, "datasetSeed": dataset_seed,
                                 "accountCount": len(labels), "transactionCount": len(graph["transactions"]),
                                 "positiveCount": sum(labels.values()), "graphSha256": sha(graph), "labelsSha256": sha(labels),
                                 "overlapWithValidation": len(set(labels) & set(validation_labels))})
        all_normal = [{"actual": value, "predicted": False, "score": 0} for value in labels.values()]
        runs.append({"scenario": scenario, "method": "ALL_NORMAL", "seed": None,
                     "metrics": classification_metrics(all_normal), "seconds": 0})
        for method, seeds, band_rows in [("EXACT_JACCARD", [42], 2), ("SIM_CSGM", [42,43,44,45,46], 2),
                                         ("PROB_CSGM", [42,43,44,45,46], 5)]:
            for seed in seeds:
                parameters = {**config, "method": method, "seed": seed, "rows": band_rows,
                              "bands": 20 if method == "PROB_CSGM" else 50}
                began = time.perf_counter()
                result = detect_collaborative_aml({**graph, **parameters})
                elapsed = time.perf_counter() - began
                prediction_rows = rows_for(result, labels, strata)
                metrics = classification_metrics(prediction_rows)
                filename = f"{scenario.lower()}_{method.lower()}_{seed}"
                save(out / f"{filename}.json", result)
                with (out / f"{filename}.csv").open("w", newline="") as f:
                    writer = csv.DictWriter(f, fieldnames=["accountId","actual","predicted","score","stratum"])
                    writer.writeheader();writer.writerows(prediction_rows)
                strata_metrics = {kind: classification_metrics([r for r in prediction_rows if r["stratum"] == kind])
                                  for kind in sorted(set(strata.values()))}
                runs.append({"scenario": scenario, "method": method, "seed": seed, "parameters": parameters,
                             "metrics": metrics, "strataMetrics": strata_metrics, "seconds": elapsed,
                             "summary": result["summary"], "inputSnapshotSha256": result["inputSnapshotSha256"]})
    aggregate = []
    for scenario in ["CONTROLLED", "SPARSER_POSITIVES"]:
        for method in ["ALL_NORMAL", "EXACT_JACCARD", "SIM_CSGM", "PROB_CSGM"]:
            selected = [r for r in runs if r["scenario"] == scenario and r["method"] == method]
            metrics = {}
            for key in ["accuracy","precision","recall","f1","falsePositiveRate","balancedAccuracy","rocAuc","averagePrecision"]:
                values = [r["metrics"][key] for r in selected if r["metrics"][key] is not None]
                metrics[key] = {"mean": statistics.fmean(values) if values else None,
                                "sampleStd": statistics.stdev(values) if len(values)>1 else None}
            aggregate.append({"scenario": scenario, "method": method, "repeatCount": len(selected), "metrics": metrics,
                              "meanSeconds": statistics.fmean(r["seconds"] for r in selected)})
    source_files = [Path(__file__), ROOT / "workers/risk-analytics-service/collaborative_aml.py",
                    ROOT / "workers/risk-analytics-service/aml_metrics.py"]
    manifest = {
        "executedAtUtc": datetime.now(timezone.utc).isoformat(), "python": sys.version.split()[0],
        "scope": "LOCAL_CONTROLLED_SYNTHETIC_NOT_AMLSIM_AMLWORLD_OR_ALIPAY_ECB",
        "referenceDoi": "10.1145/3696410.3714576", "validationGraphSeed": 10001,
        "frozenParametersSha256": sha(freeze), "frozenParameters": config, "datasets": dataset_manifest,
        "codeSha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files},
        "runs": runs, "aggregate": aggregate,
        "limits": ["GENERATOR_LABELS_ONLY", "NO_REAL_BUSINESS_ACCURACY_ESTIMATE", "NO_PUBLISHED_SCORE_REPRODUCTION",
                   "NO_BUSINESS_ACCEPTANCE_THRESHOLD", "NO_DISTRIBUTED_NETWORK_OR_CRYPTOGRAPHIC_PRIVACY_TEST"],
    }
    save(out / "benchmark_results.json", manifest)
    print(json.dumps({"datasets":dataset_manifest,"threshold":config["threshold"],"aggregate":aggregate},ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="CSGM离线识别和独立验证测试")
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("benchmark");b.add_argument("--output", type=Path, required=True)
    d = sub.add_parser("detect");d.add_argument("--input",type=Path,required=True)
    d.add_argument("--output",type=Path,required=True);d.add_argument("--labels",type=Path)
    args = parser.parse_args()
    benchmark(args) if args.command == "benchmark" else detect_file(args)
