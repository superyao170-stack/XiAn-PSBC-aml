from __future__ import annotations

import json
import os
import urllib.error
import urllib.request


def request(method: str, url: str, body: dict | None = None, token: str | None = None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=15) as response:
        payload = response.read()
        return json.loads(payload.decode("utf-8")) if payload else {}


base = os.environ.get("TUGRAPH_REST_URL", "http://127.0.0.1:7070").rstrip("/")
graph = os.environ.get("TUGRAPH_DATABASE", "BankGraph")
login = request("POST", f"{base}/login", {
    "user": os.environ["TUGRAPH_USERNAME"],
    "password": os.environ["TUGRAPH_PASSWORD"],
})
token = login["jwt"]
graphs = request("GET", f"{base}/db", token=token)
if graph not in graphs:
    request("POST", f"{base}/db", {
        "name": graph,
        "config": {
            "description": "BankGraph illegal behavior recognition",
            "max_size_GB": 1024,
            "async": False,
        },
    }, token)
    print(f"Created TuGraph subgraph: {graph}")
else:
    print(f"TuGraph subgraph already exists: {graph}")

result = request("POST", f"{base}/cypher", {
    "graph": graph,
    "script": "RETURN 1 AS ok",
}, token)
print(f"TuGraph query succeeded: {graph}")


def ensure_schema(script: str, name: str) -> None:
    try:
        request("POST", f"{base}/cypher", {
            "graph": graph,
            "script": script,
        }, token)
        print(f"Created TuGraph schema: {name}")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        if "exist" in detail.lower() or "already" in detail.lower():
            print(f"TuGraph schema already exists: {name}")
            return
        raise RuntimeError(f"Cannot create TuGraph schema {name}: {detail}") from exc


properties = (
    "'graphId','graphId','string',false,"
    "'bankCode','string',true,'accountHash','string',true,"
    "'sourceRecordId','string',true,'amount','string',true,"
    "'currency','string',true,'occurredAt','string',true,"
    "'nodeType','string',true,'caseId','string',true,"
    "'workspaceId','string',true,'summary','string',true,"
    "'eventName','string',true,'eventType','string',true,"
    "'eventText','string',true"
)
for label in ("Case", "Evidence", "Event", "Customer", "Account", "Transaction"):
    ensure_schema(f"CALL db.createVertexLabel('{label}',{properties})", f"vertex:{label}")

for label in (
    "TRANSFER", "INVOLVES", "HAS_TRANSACTION", "CONTAINS_EVIDENCE",
    "CONTAINS_EVENT", "SUPPORTS_EVENT", "OWNS_ACCOUNT",
):
    ensure_schema(
        f"CALL db.createEdgeLabel('{label}','[]','graphId','string',true)",
        f"edge:{label}",
    )
