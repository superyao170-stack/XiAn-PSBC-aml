const DATE_TIME_PATTERN = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?/
const ZONE_SUFFIX_PATTERN = /(Z|[+-]\d{2}:?\d{2})$/i

/** Format backend timestamps as China Standard Time: YYYY-MM-DD HH:mm:ss. */
export function formatDateTime(value: unknown): string {
  if (value === null || value === undefined || value === '') return '-'
  const raw = String(value).trim()
  if (!DATE_TIME_PATTERN.test(raw)) return raw

  const iso = raw.replace(' ', 'T')
  const normalized = ZONE_SUFFIX_PATTERN.test(iso) ? iso : `${iso}+08:00`
  const date = new Date(normalized)
  if (Number.isNaN(date.getTime())) return raw

  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false,
  }).formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(item => item.type === type)?.value || ''
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}:${part('second')}`
}

export function dateTimeCell(_row: unknown, _column: unknown, value: unknown): string {
  return formatDateTime(value)
}
