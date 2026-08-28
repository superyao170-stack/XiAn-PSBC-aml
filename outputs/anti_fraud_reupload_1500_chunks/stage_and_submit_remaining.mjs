import fs from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";

const baseUrl = "http://localhost:3030";
const here = path.resolve(".");
const uploadRoot = path.resolve("../../workers/anti-fraud-case-identification/uploads");
const authHeader = (await fs.readFile("/private/tmp/datagraph_auth_reupload", "utf8"))
  .trim().replace(/^Authorization:\s*/i, "");
const validation = JSON.parse(await fs.readFile("validation_results.json", "utf8"));
let results = JSON.parse(await fs.readFile("submission_results.json", "utf8"));
const submittedParts = new Set(results.map((item) => item.part));

async function readJson(response, label) {
  const body = await response.json();
  if (!response.ok || body.code !== 200) {
    throw new Error(`${label} failed: HTTP ${response.status}, ${body.message ?? JSON.stringify(body)}`);
  }
  return body;
}

for (const part of validation) {
  if (submittedParts.has(part.part)) continue;
  const token = randomUUID();
  const tokenDir = path.join(uploadRoot, token);
  await fs.mkdir(tokenDir, { recursive: false });
  await fs.copyFile(path.join(here, part.fileName), path.join(tokenDir, "cases.csv"));

  const request = {
    bankCode: "中国测试银行",
    workspaceId: 1,
    jobType: "STRUCTURED",
    jobName: `1500条历史反欺诈案例重新上传-${String(part.part).padStart(2, "0")}/30`,
    scenarioCode: "ANTI_FRAUD",
    inputParams: JSON.stringify({
      workflow: "ANTI_FRAUD_CASE_PIPELINE",
      processingMode: "BATCH",
      recognitionMode: "HISTORICAL",
      uploadToken: token,
      caseCount: part.caseCount,
      sourceFileName: part.fileName,
    }),
    steps: [
      { stepName: "数据校验", stepType: "VALIDATE" },
      { stepName: "案例入库", stepType: "PERSIST" },
      { stepName: "历史案例抽取并自动入图", stepType: "HISTORY_FRAMEWORK" },
    ],
  };
  const response = await fetch(`${baseUrl}/api/v1/analysis/jobs`, {
    method: "POST",
    headers: { Authorization: authHeader, "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  const created = await readJson(response, `create part ${part.part}`);
  const result = {
    part: part.part,
    fileName: part.fileName,
    caseCount: part.caseCount,
    firstCaseId: part.firstCaseId,
    lastCaseId: part.lastCaseId,
    jobId: created.data.jobId,
    status: created.data.status,
  };
  results.push(result);
  results.sort((a, b) => a.part - b.part);
  await fs.writeFile("submission_results.json", JSON.stringify(results, null, 2) + "\n", "utf8");
  console.log(JSON.stringify(result));
}

console.log(JSON.stringify({ submittedParts: results.length, submittedCases: results.reduce((sum, item) => sum + item.caseCount, 0) }));
