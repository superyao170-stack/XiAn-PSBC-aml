import fs from 'node:fs/promises';
import path from 'node:path';
import { Workbook } from '@oai/artifact-tool';

const sourceRoot = path.resolve(process.argv[2]);
const outputDir = path.resolve(process.argv[3] || 'outputs');
const previewDir = path.resolve(process.argv[4] || '/private/tmp/codex-antifraud-csv-previews');

const jobs = [
  {
    sourceFolder: 'analysis_text_cases',
    outputName: 'anti_fraud_analysis_text_cases_1500.csv',
    sheetName: 'HistoricalCases',
    columns: [
      ['basic_info', 'basic_info.json'],
      ['customers', 'customers.json'],
      ['accounts', 'accounts.json'],
      ['devices', 'devices.json'],
      ['text_analysis', 'text_analysis.json'],
    ],
  },
  {
    sourceFolder: 'event_chain_cases',
    outputName: 'anti_fraud_event_chain_cases_500.csv',
    sheetName: 'NewCases',
    columns: [
      ['basic_info', 'basic_info.json'],
      ['customers', 'customers.json'],
      ['accounts', 'accounts.json'],
      ['devices', 'devices.json'],
      ['event_chain', 'event_chain.json'],
    ],
  },
];

function csvCell(value) {
  return `"${value.replaceAll('"', '""')}"`;
}

async function caseDirectories(folderPath) {
  const entries = await fs.readdir(folderPath, { withFileTypes: true });
  return entries
    .filter((entry) => entry.isDirectory() && !entry.name.startsWith('.'))
    .map((entry) => entry.name)
    .sort((left, right) => left.localeCompare(right, 'en'));
}

async function buildCsv(job) {
  const folderPath = path.join(sourceRoot, job.sourceFolder);
  const caseNames = await caseDirectories(folderPath);
  const rows = [job.columns.map(([header]) => header).join(',')];
  const caseIds = new Set();

  for (const caseName of caseNames) {
    const cells = [];
    for (const [, fileName] of job.columns) {
      const filePath = path.join(folderPath, caseName, fileName);
      const parsed = JSON.parse(await fs.readFile(filePath, 'utf8'));
      cells.push(csvCell(JSON.stringify(parsed)));
      if (fileName === 'basic_info.json') {
        const caseId = String(parsed?.case_id || '').trim();
        if (!caseId) throw new Error(`${caseName}/basic_info.json 缺少 case_id`);
        if (caseIds.has(caseId)) throw new Error(`${job.sourceFolder} 存在重复 case_id: ${caseId}`);
        caseIds.add(caseId);
      }
    }
    rows.push(cells.join(','));
  }

  const csvText = `${rows.join('\n')}\n`;
  const outputPath = path.join(outputDir, job.outputName);
  await fs.mkdir(outputDir, { recursive: true });
  await fs.writeFile(outputPath, csvText, 'utf8');

  const workbook = await Workbook.fromCSV(csvText, { sheetName: job.sheetName });
  const inspection = await workbook.inspect({
    kind: 'table',
    range: `${job.sheetName}!A1:E3`,
    include: 'values',
    tableMaxRows: 3,
    tableMaxCols: 5,
    tableMaxCellChars: 120,
    maxChars: 3500,
  });
  const sheet = workbook.worksheets.getItem(job.sheetName);
  sheet.getRange('A1:E4').format.columnWidthPx = 180;
  sheet.getRange('A1:E1').format.rowHeightPx = 28;
  sheet.getRange('A2:E4').format.rowHeightPx = 42;
  sheet.getRange('A1:E1').format = {
    fill: '#1F4E78',
    font: { bold: true, color: '#FFFFFF' },
  };
  const preview = await workbook.render({
    sheetName: job.sheetName,
    range: 'A1:E4',
    scale: 1,
    format: 'png',
  });
  await fs.mkdir(previewDir, { recursive: true });
  await fs.writeFile(
    path.join(previewDir, `${job.sourceFolder}.png`),
    new Uint8Array(await preview.arrayBuffer()),
  );

  return {
    outputPath,
    rowCount: caseNames.length,
    uniqueCaseIdCount: caseIds.size,
    headers: job.columns.map(([header]) => header),
    bytes: Buffer.byteLength(csvText),
    inspection: inspection.ndjson,
  };
}

const results = [];
for (const job of jobs) results.push(await buildCsv(job));
console.log(JSON.stringify(results, null, 2));
