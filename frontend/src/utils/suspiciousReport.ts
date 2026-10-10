type SuspiciousReport = {
  analysisText?: string
  analysisTexts?: Record<string, unknown>
}

export function formatSuspiciousReport(report: SuspiciousReport | null | undefined, scenarioCode: string): string {
  const direct = String(report?.analysisText || '').trim()
  const sections = Object.entries(report?.analysisTexts || {})
    .filter((entry): entry is [string, string] => typeof entry[1] === 'string' && Boolean(entry[1].trim()))
  const joined = sections.map(([, value]) => value.trim()).join('\n\n')

  // A separately edited report takes precedence over its original sections.
  if (direct && direct !== joined) return direct
  if (scenarioCode !== 'AML') return direct || joined

  return sections.map(([key, value]) => {
    const text = value.trim()
    if (!/^analysis_text[12]$/.test(key) || text === key || text.startsWith(`${key}\n`)) return text
    return `${key}\n${text}`
  }).join('\n\n') || direct
}
