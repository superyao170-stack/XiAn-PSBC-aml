import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
WORKER = HERE.parents[1] / "workers" / "anti-fraud-case-identification"
sys.path.insert(0, str(WORKER))

from framework_extraction import run_pipeline  # noqa: E402

manifest = json.loads((HERE / "manifest.json").read_text(encoding="utf-8"))
results = []
for part in manifest["parts"][5:]:
    response = run_pipeline({
        "sourcePath": str(HERE / part["fileName"]),
        "processingMode": "BATCH",
        "recognitionMode": "HISTORICAL",
        "pipelineBudgetSeconds": 60,
        "validateOnly": True,
    })
    if response.get("status") != "SUCCEEDED" or response.get("caseCount") != part["caseCount"]:
        raise RuntimeError(f"part {part['part']} validation failed: {response}")
    result = {
        "part": part["part"],
        "fileName": part["fileName"],
        "caseCount": response["caseCount"],
        "sourceSha256": response["sourceSha256"],
        "firstCaseId": response["caseIds"][0],
        "lastCaseId": response["caseIds"][-1],
    }
    results.append(result)
    print(json.dumps(result, ensure_ascii=False), flush=True)

(HERE / "validation_results.json").write_text(
    json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
print(json.dumps({"validatedParts": len(results), "validatedCases": sum(x["caseCount"] for x in results)}))
