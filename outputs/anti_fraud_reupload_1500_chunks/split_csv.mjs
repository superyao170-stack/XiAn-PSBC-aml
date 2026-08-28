import fs from "node:fs/promises";
import path from "node:path";
import { Workbook } from "@oai/artifact-tool";

const source = path.resolve("../anti_fraud_analysis_text_cases_1500.csv");
const outputDir = path.resolve(".");
const chunkSize = 50;
const expectedHeaders = ["basic_info", "customers", "accounts", "devices", "text_analysis"];

const csvText = await fs.readFile(source, "utf8");
const workbook = await Workbook.fromCSV(csvText, { sheetName: "Cases" });
const sheet = workbook.worksheets.getItem("Cases");
const values = sheet.getUsedRange(true).values;
const headers = values[0].map((value) => String(value ?? ""));
if (JSON.stringify(headers) !== JSON.stringify(expectedHeaders)) {
  throw new Error(`Unexpected headers: ${JSON.stringify(headers)}`);
}

const rows = values.slice(1).map((row) => row.map((value) => String(value ?? "")));
if (rows.length !== 1500) throw new Error(`Expected 1500 rows, received ${rows.length}`);

const encode = (value) => {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
};

const caseIds = new Set();
for (const [index, row] of rows.entries()) {
  if (row.length !== 5) throw new Error(`Row ${index + 2} has ${row.length} fields`);
  const basicInfo = JSON.parse(row[0]);
  const caseId = basicInfo.case_id;
  if (!caseId) throw new Error(`Row ${index + 2} has no case_id`);
  if (caseIds.has(caseId)) throw new Error(`Duplicate case_id: ${caseId}`);
  caseIds.add(caseId);
  for (const cell of row) JSON.parse(cell);
}

const manifest = [];
for (let offset = 0; offset < rows.length; offset += chunkSize) {
  const chunk = rows.slice(offset, offset + chunkSize);
  const number = offset / chunkSize + 1;
  const fileName = `anti_fraud_historical_1500_part_${String(number).padStart(2, "0")}.csv`;
  const content = [headers, ...chunk].map((row) => row.map(encode).join(",")).join("\r\n") + "\r\n";
  await fs.writeFile(path.join(outputDir, fileName), content, "utf8");
  manifest.push({
    part: number,
    fileName,
    caseCount: chunk.length,
    firstCaseId: JSON.parse(chunk[0][0]).case_id,
    lastCaseId: JSON.parse(chunk.at(-1)[0]).case_id,
  });
}

await fs.writeFile(path.join(outputDir, "manifest.json"), JSON.stringify({
  source: path.basename(source),
  headers,
  totalCases: rows.length,
  uniqueCaseIds: caseIds.size,
  chunkSize,
  chunkCount: manifest.length,
  parts: manifest,
}, null, 2) + "\n", "utf8");

console.log(JSON.stringify({ totalCases: rows.length, uniqueCaseIds: caseIds.size, chunkCount: manifest.length }));
