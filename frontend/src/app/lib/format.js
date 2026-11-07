// 숫자·시각 표시 형식 (원화, 한국 시간)

const won = new Intl.NumberFormat('ko-KR')
const rate = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const oneDecimal = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const timeFmt = new Intl.DateTimeFormat('ko-KR', {
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, timeZone: 'Asia/Seoul',
})
const hmFmt = new Intl.DateTimeFormat('ko-KR', {
  hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul',
})

const isNum = (v) => v !== null && v !== undefined && v !== '' && Number.isFinite(Number(v))

export const formatPrice = (v) => (isNum(v) ? `${won.format(Number(v))}원` : '—')
export const formatNumber = (v) => (isNum(v) ? won.format(Number(v)) : '—')
export const formatVolume = (v) => (isNum(v) ? `${won.format(Number(v))}주` : '—')

export const formatSignedPrice = (v) => {
  if (!isNum(v)) return '—'
  const n = Number(v)
  return `${n > 0 ? '+' : ''}${won.format(n)}원`
}

// 억원 단위 금액: 1조 이상은 "171.5조", 그 미만은 "9,385억"
export const formatEok = (v) => {
  if (!isNum(v)) return '—'
  const n = Number(v)
  if (Math.abs(n) >= 10000) return `${oneDecimal.format(n / 10000)}조`
  return `${won.format(n)}억`
}

export const formatRate = (v) => {
  if (!isNum(v)) return '—'
  const n = Number(v)
  return `${n > 0 ? '+' : ''}${rate.format(n)}%`
}

// 상승 red / 하락 blue / 보합 (국내 표기 관례)
export const directionClass = (v) => {
  if (!isNum(v) || Number(v) === 0) return 'flat'
  return Number(v) > 0 ? 'up' : 'down'
}

export const formatClock = (value) => (value ? timeFmt.format(new Date(value)) : '—')
export const formatHourMinute = (value) => (value ? hmFmt.format(new Date(value)) : '')

const dayTimeFmt = new Intl.DateTimeFormat('ko-KR', {
  month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul',
})

// 차트 축 표시: 초봉 시:분:초 · 분봉 시:분(며칠에 걸치면 월/일 포함) · 일주봉 월/일 · 월봉 연.월 · 연봉 연
export const formatChartTime = (value, interval, multiDay = false) => {
  if (!value) return ''
  if (['1d', '1w'].includes(interval)) return formatDay(value)
  if (interval === '1mo') return String(value).slice(0, 7).replace('-', '.')
  if (interval === '1y') return String(value).slice(0, 4)
  if (interval?.endsWith('s')) return timeFmt.format(new Date(value))
  return multiDay ? dayTimeFmt.format(new Date(value)) : hmFmt.format(new Date(value))
}

export const formatDay = (value) => {
  if (!value) return ''
  const [, m, d] = String(value).split('-')
  return `${Number(m)}/${Number(d)}`
}
