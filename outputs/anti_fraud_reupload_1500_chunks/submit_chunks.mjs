import fs from "node:fs/promises";
import path from "node:path";

const baseUrl = "http://localhost:3030";
const authHeader = (await fs.readFile("/private/tmp/datagraph_auth_reupload", "utf8")).trim();
const manifest = JSON.parse(await fs.readFile("manifest.json", "utf8"));
const results = [];

async function readJson(response, label) {
  const body = await response.json();
  if (!response.ok || body.code !== 200) {
    throw new Error(`${label} failed: HTTP ${response.status}, ${body.message ?? JSON.stringify(body)}`);
  }
  return body;
}

for (const part of manifest.parts) {
  const filePath = path.resolve(part.fileName);
  const form = new FormData();
  form.append("file", new Blob([await fs.readFile(filePath)], { type: "text/csv" }), part.fileName);
  const uploadResponse = await fetch(
    `${baseUrl}/api/v1/analysis/anti-fraud-case-batch-file?recognitionMode=HISTORICAL`,
    { method: "POST", headers: { Authorization: authHeader.replace(/^Authorization:\s*/i, "") }, body: form },
  );
  const upload = await readJson(uploadResponse, `upload part ${part.part}`);
  if (upload.data?.caseCount !== part.caseCount) {
    throw new Error(`part ${part.part} expected ${part.caseCount} cases, backend saw ${upload.data?.caseCount}`);
  }

  const inputParams = {
    workflow: "ANTI_FRAUD_CASE_PIPELINE",
    processingMode: "BATCH",
    recognitionMode: "HISTORICAL",
    uploadToken: upload.data.uploadToken,
    caseCount: upload.data.caseCount,
    sourceFileName: upload.data.fileName,
  };
  const request = {
    bankCode: "中国测试银行",
    workspaceId: 1,
    jobType: "STRUCTURED",
    jobName: `1500条历史反欺诈案例重新上传-${String(part.part).padStart(2, "0")}/${manifest.chunkCount}`,
    scenarioCode: "ANTI_FRAUD",
    inputParams: JSON.stringify(inputParams),
    steps: [
      { stepName: "数据校验", stepType: "VALIDATE" },
      { stepName: "案例入库", stepType: "PERSIST" },
      { stepName: "历史案例抽取并自动入图", stepType: "HISTORY_FRAMEWORK" },
    ],
  };
  const createResponse = await fetch(`${baseUrl}/api/v1/analysis/jobs`, {
    method: "POST",
    headers: { Authorization: authHeader.replace(/^Authorization:\s*/i, ""), "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  const created = await readJson(createResponse, `create part ${part.part}`);
  const result = {
    part: part.part,
    fileName: part.fileName,
    caseCount: upload.data.caseCount,
    firstCaseId: part.firstCaseId,
    lastCaseId: part.lastCaseId,
    jobId: created.data.jobId,
    status: created.data.status,
  };
  results.push(result);
  await fs.writeFile("submission_results.json", JSON.stringify(results, null, 2) + "\n", "utf8");
  console.log(JSON.stringify(result));
}

console.log(JSON.stringify({ submittedParts: results.length, submittedCases: results.reduce((sum, item) => sum + item.caseCount, 0) }));
