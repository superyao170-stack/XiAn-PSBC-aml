import fs from "node:fs/promises";

const idsPath = "/private/tmp/anti_fraud_report_400_remaining.txt";
const authPath = "/private/tmp/datagraph_auth_report400_v2";
const resultPath = "/private/tmp/anti_fraud_report_400_node_results.ndjson";
const ids = (await fs.readFile(idsPath, "utf8")).split(/\r?\n/).filter(Boolean);
const authorization = (await fs.readFile(authPath, "utf8")).trim().replace(/^Authorization:\s*/i, "");
await fs.writeFile(resultPath, "", "utf8");

let nextIndex = 0;
let completed = 0;
let succeeded = 0;
let failed = 0;
let httpErrors = 0;

async function runOne(caseId) {
  try {
    const response = await fetch("http://localhost:3030/api/v1/case-processing/actions/REPORT", {
      method: "POST",
      headers: { Authorization: authorization, "Content-Type": "application/json" },
      body: JSON.stringify({ caseIds: [caseId] }),
    });
    const body = await response.json();
    const result = body?.data?.results?.[0];
    const item = {
      caseId,
      httpStatus: response.status,
      code: body?.code,
      status: result?.status ?? "FAILED",
      error: result?.error ?? body?.message ?? null,
    };
    if (response.ok && body?.code === 200 && result?.status === "SUCCEEDED") succeeded += 1;
    else failed += 1;
    await fs.appendFile(resultPath, JSON.stringify(item) + "\n", "utf8");
  } catch (error) {
    httpErrors += 1;
    await fs.appendFile(resultPath, JSON.stringify({ caseId, status: "HTTP_ERROR", error: String(error) }) + "\n", "utf8");
  } finally {
    completed += 1;
    if (completed % 10 === 0 || completed === ids.length) {
      console.log(JSON.stringify({ completed, total: ids.length, succeeded, failed, httpErrors }));
    }
  }
}

async function worker() {
  while (true) {
    const index = nextIndex++;
    if (index >= ids.length) return;
    await runOne(ids[index]);
  }
}

await Promise.all([worker(), worker()]);
console.log(JSON.stringify({ status: "FINISHED", requested: ids.length, succeeded, failed, httpErrors }));
