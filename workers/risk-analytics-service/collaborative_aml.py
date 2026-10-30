"""CSGM-derived, two-institution offline baseline. No labels enter inference.

Reference: Tian et al., WWW 2025, doi:10.1145/3696410.3714576.
This is an independent implementation, not a reproduction of published scores.
Bloom filters compress membership; they are not cryptographic privacy protection.
"""
from __future__ import annotations

import hashlib
import json
import math
import random
from collections import defaultdict, deque
from datetime import datetime, timezone

VERSION = "1.0.0"
PRIME = (1 << 61) - 1


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def identifier(value, name):
    if not isinstance(value, (str, int)) or isinstance(value, bool) or not str(value).strip():
        raise ValueError(f"{name} requires a nonempty string or integer")
    return str(value).strip()


def bounded_int(value, name, lo, hi):
    if isinstance(value, bool) or not isinstance(value, int) or not lo <= value <= hi:
        raise ValueError(f"{name} must be an integer between {lo} and {hi}")
    return value


def timestamp(value):
    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Transaction/window timestamps must include a timezone")
    return parsed.astimezone(timezone.utc)


class BloomFilter:
    def __init__(self, bits, hashes):
        self.bits, self.hashes = bits, hashes
        self.data = bytearray((bits + 7) // 8)

    def positions(self, value):
        # Double hashing avoids Python's process-randomized hash().
        h = hashlib.sha256(value.encode()).digest()
        a, b = int.from_bytes(h[:16], "big"), int.from_bytes(h[16:], "big") | 1
        return [(a + i * b) % self.bits for i in range(self.hashes)]

    def add(self, value):
        for p in self.positions(value): self.data[p // 8] |= 1 << (p % 8)

    def contains(self, value):
        return all(self.data[p // 8] & (1 << (p % 8)) for p in self.positions(value))


def prepare_graph(payload):
    accounts = payload.get("accounts", [])
    transactions = payload.get("transactions", [])
    if not isinstance(accounts, list) or not accounts or len(accounts) > 20000:
        raise ValueError("accounts requires 1 to 20000 accounts")
    if not isinstance(transactions, list) or len(transactions) > 200000:
        raise ValueError("transactions must be a list with at most 200000 rows")
    banks = {}
    for account in accounts:
        key = identifier(account.get("id"), "account.id")
        if key in banks: raise ValueError("Duplicate account id")
        banks[key] = identifier(account.get("institution"), "account.institution")
    if len(set(banks.values())) != 2:
        raise ValueError("Offline CSGM requires exactly two institutions")
    minimum = float(payload.get("minAmount", 0.0))
    if not math.isfinite(minimum) or minimum < 0:
        raise ValueError("minAmount must be finite and nonnegative")
    start, end = payload.get("windowStart"), payload.get("windowEnd")
    if bool(start) != bool(end): raise ValueError("Supply both windowStart and windowEnd")
    start, end = (timestamp(start), timestamp(end)) if start else (None, None)
    if start and start >= end: raise ValueError("windowStart must be before windowEnd")
    seen, pairs, currencies = set(), {}, set()
    excluded = 0
    for tx in transactions:
        tid = identifier(tx.get("transactionId"), "transactionId")
        if tid in seen: raise ValueError("Duplicate transactionId")
        seen.add(tid)
        source, target = identifier(tx.get("source"), "source"), identifier(tx.get("target"), "target")
        if source not in banks or target not in banks: raise ValueError("Transaction references unknown account")
        amount = float(tx.get("amount", 0))
        if not math.isfinite(amount) or amount < 0: raise ValueError("amount must be finite and nonnegative")
        if start and not start <= timestamp(tx.get("occurredAt")) < end:
            excluded += 1
            continue
        currency = identifier(tx.get("currency", "CNY"), "currency")
        currencies.add(currency)
        if len(currencies) > 1: raise ValueError("Use a single normalized currency per experiment")
        key = canonical([source, target])
        if key not in pairs:
            pairs[key] = {"source": source, "target": target, "amount": 0.0, "transactionIds": [], "evidenceRefs": []}
        pair = pairs[key]
        pair["amount"] += amount
        pair["transactionIds"].append(tid)
        ref = tx.get("evidenceRef")
        if ref is not None: pair["evidenceRefs"].append(str(ref))
    retained = {key: value for key, value in pairs.items() if value["amount"] >= minimum}
    forward, backward = defaultdict(list), defaultdict(list)
    for key, edge in sorted(retained.items()):
        forward[edge["source"]].append((edge["target"], key))
        backward[edge["target"]].append((edge["source"], key))
        edge["transactionIds"].sort()
        edge["evidenceRefs"] = sorted(set(edge["evidenceRefs"]))
    snapshot = {"accounts": sorted(banks.items()), "aggregatedEdges": retained}
    return banks, retained, forward, backward, {
        "inputAccountCount": len(banks), "inputTransactionCount": len(transactions),
        "excludedByWindow": excluded, "aggregatedPairCount": len(pairs),
        "retainedPairCount": len(retained), "filteredSmallPairCount": len(pairs) - len(retained),
        "crossInstitutionPairCount": sum(banks[e["source"]] != banks[e["target"]] for e in retained.values()),
        "currency": next(iter(currencies), "CNY"), "graphSha256": sha(snapshot),
    }


def discover(root, adjacency, banks, depth):
    queue = deque([(root, 0, (root,), ())])
    visited, boundary, members = {root}, set(), {root}
    supporting_edges = set()
    while queue:
        current, hop, path, path_edges = queue.popleft()
        if hop >= depth: continue
        for neighbor, edge_key in adjacency.get(current, []):
            if banks[current] != banks[neighbor]:
                boundary.add(edge_key)
                members.update(path)
                members.add(neighbor)
                supporting_edges.update(path_edges + (edge_key,))
            elif neighbor not in visited:
                visited.add(neighbor)
                queue.append((neighbor, hop + 1, path + (neighbor,), path_edges + (edge_key,)))
    return boundary, members, supporting_edges


def signatures(items, coefficients, rows):
    integers = [int(sha(item)[:16], 16) % PRIME for item in sorted(items)]
    signature = [min((a * x + b) % PRIME for x in integers) for a, b in coefficients]
    return [sha(signature[i:i + rows]) for i in range(0, len(signature), rows)]


def detect_collaborative_aml(payload):
    method = str(payload.get("method", "SIM_CSGM")).upper()
    if method not in {"SIM_CSGM", "PROB_CSGM", "EXACT_JACCARD"}:
        raise ValueError("method must be SIM_CSGM, PROB_CSGM or EXACT_JACCARD")
    bands = bounded_int(payload.get("bands", 50), "bands", 1, 256)
    rows = bounded_int(payload.get("rows", 2), "rows", 1, 8)
    depth = bounded_int(payload.get("maxDepth", 2), "maxDepth", 1, 6)
    minimum = bounded_int(payload.get("minSetSize", 5), "minSetSize", 1, 10000)
    bits = bounded_int(payload.get("bloomBits", 500000), "bloomBits", 8, 3000000)
    hashes = bounded_int(payload.get("bloomHashes", 7), "bloomHashes", 1, 16)
    seed = bounded_int(payload.get("seed", 42), "seed", 0, (1 << 32) - 1)
    if bands * ((bits + 7) // 8) * 4 > 64000000:
        raise ValueError("Bloom filter memory exceeds 64 MB offline service limit")
    threshold = float(payload.get("threshold", 0.3))
    if not math.isfinite(threshold) or not 0 < threshold <= 1:
        raise ValueError("threshold must be in (0, 1]")
    banks, edges, forward, backward, preprocessing = prepare_graph(payload)
    institutions = sorted(set(banks.values()))
    sets, members, supporting_edges = {}, {}, {}
    for root in sorted(banks):
        for direction, adjacency in [("SCATTER", forward), ("GATHER", backward)]:
            items, nodes, support = discover(root, adjacency, banks, depth)
            if len(items) >= minimum:
                key = (banks[root], direction, root)
                sets[key], members[key] = items, nodes
                supporting_edges[key] = support
    rng = random.Random(seed)
    coefficients = [(rng.randrange(1, PRIME), rng.randrange(PRIME)) for _ in range(bands * rows)]
    band_values, filters = {}, {}
    if method != "EXACT_JACCARD":
        for bank in institutions:
            for direction in ["SCATTER", "GATHER"]:
                filters[bank, direction] = [BloomFilter(bits, hashes) for _ in range(bands)]
        for key, items in sets.items():
            values = signatures(items, coefficients, rows)
            band_values[key] = values
            for index, value in enumerate(values): filters[key[:2]][index].add(value)
    scores = {root: 0.0 for root in banks}
    predicted = set()
    groups = []
    for key, items in sorted(sets.items()):
        bank, direction, root = key
        other = institutions[1] if bank == institutions[0] else institutions[0]
        reverse = "GATHER" if direction == "SCATTER" else "SCATTER"
        if method == "EXACT_JACCARD":
            candidates = [s for k, s in sets.items() if k[:2] == (other, reverse)]
            score = max((len(items & s) / len(items | s) for s in candidates), default=0.0)
            hits = None
            hit = score >= threshold
        else:
            hits = sum(bf.contains(value) for bf, value in zip(filters[other, reverse], band_values[key]))
            fraction = hits / bands
            score = fraction ** (1 / rows)
            hit = hits > 0 if method == "PROB_CSGM" else fraction >= threshold ** rows
        # Boundary nodes are visible in the local cross-institution transaction.
        # Remote internal paths and matching endpoint identities cannot be obtained from Bloom filters.
        for node in members[key]: scores[node] = max(scores[node], score)
        if hit:
            predicted.update(members[key])
            edge_keys = sorted(items)
            support_keys = sorted(supporting_edges[key])
            groups.append({
                "candidateId": "CSGM-" + sha([key, edge_keys])[:24],
                "institution": bank, "endpointId": root, "direction": direction,
                "score": score, "matchedBands": hits, "boundaryPairCount": len(items),
                "accountIds": sorted(members[key]),
                "boundaryTransactionIds": sorted({tid for e in edge_keys for tid in edges[e]["transactionIds"]}),
                "transactionIds": sorted({tid for e in support_keys for tid in edges[e]["transactionIds"]}),
                "evidenceRefs": sorted({ref for e in support_keys for ref in edges[e]["evidenceRefs"]}),
                "reviewStatus": "PENDING", "scope": "LOCAL_ENDPOINT_AND_VISIBLE_BOUNDARY",
            })
    evidence_by_node = defaultdict(list)
    for group in groups:
        for node in group["accountIds"]: evidence_by_node[node].append(group["candidateId"])
    evidence = [{
        "subjectId": root, "institution": banks[root], "score": scores[root],
        "decision": "SUSPECTED" if root in predicted else "NORMAL",
        "reasonCodes": ["CROSS_INSTITUTION_SCATTER_GATHER"] if root in predicted else [],
        "pathEvidence": evidence_by_node[root], "contributions": [],
    } for root in sorted(banks)]
    parameters = {"method": method, "threshold": threshold, "rows": rows, "bands": bands,
                  "hashFunctions": rows * bands, "maxDepth": depth, "minSetSize": minimum,
                  "bloomBits": bits, "bloomHashes": hashes, "seed": seed,
                  "minAmount": float(payload.get("minAmount", 0)),
                  "windowStart": payload.get("windowStart"), "windowEnd": payload.get("windowEnd")}
    summary = []
    for bank in institutions:
        for direction in ["SCATTER", "GATHER"]:
            count = sum(k[:2] == (bank, direction) for k in sets)
            fpr = (1 - math.exp(-hashes * count / bits)) ** hashes
            summary.append({"institution": bank, "direction": direction, "setCount": count,
                            "estimatedBloomFalsePositiveProbability": fpr})
    return {
        "inputSnapshotSha256": sha({"graph": preprocessing["graphSha256"], "parameters": parameters}),
        "algorithm": {"id": method, "version": VERSION, "referenceDoi": "10.1145/3696410.3714576"},
        "parameters": parameters, "preprocessing": preprocessing,
        "evidence": evidence, "candidates": groups,
        "summary": {"eligibleEndpointSets": len(sets), "suspectedAccounts": len(predicted),
                    "candidateCount": len(groups), "institutionDirections": summary,
                    "packedBloomBytesBothInstitutions": 4 * bands * ((bits + 7) // 8) if method != "EXACT_JACCARD" else 0},
        "limitations": ["OFFLINE_TWO_INSTITUTION_SIMULATION", "BLOOM_FILTER_IS_NOT_ENCRYPTION",
                        "TOPOLOGY_CANDIDATES_REQUIRE_BUSINESS_REVIEW", "NOT_PUBLISHED_BENCHMARK_REPRODUCTION",
                        "NO_REMOTE_ENDPOINT_IDENTITY_FROM_BLOOM_FILTERS"],
    }
