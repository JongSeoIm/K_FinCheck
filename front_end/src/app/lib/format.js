// 화면 공통 표기: 가격 "217,500원", 큰 금액 "3,336,059억 원", 등락 "+0.12%", 시각 "11. 4. 오후 03:14"
const number = (value, digits = 0) => Number(value).toLocaleString('ko-KR', { maximumFractionDigits: digits })
const invalid = (value) => value == null || Number.isNaN(Number(value))

export const won = (value) => (invalid(value) ? '—' : `${number(Math.round(Number(value)))}원`)

export const shares = (value) => (invalid(value) ? '—' : `${number(Math.round(Number(value)))}주`)

// 시가총액처럼 큰 금액
export function bigWon(value) {
  if (invalid(value) || Number(value) <= 0) return '—'
  const amount = Number(value)
  return amount >= 1e12 ? `${number(amount / 1e12, 1)}조 원` : `${number(Math.round(amount / 1e8))}억 원`
}

// 원 → 억 원. 금액이 없으면 0 으로 쓰지 않습니다
export function eokWon(value, emptyText = '자료 없음') {
  if (value == null) return emptyText
  const eok = Math.abs(value) / 1e8
  return `${value < 0 ? '−' : ''}${number(eok, eok < 10 ? 1 : 0)}억 원`
}

export function signedPercent(value, digits = 2) {
  if (invalid(value)) return '—'
  const amount = Number(value)
  return `${amount > 0 ? '+' : amount < 0 ? '−' : ''}${Math.abs(amount).toFixed(digits)}%`
}

export function signedWon(value) {
  if (invalid(value)) return '—'
  const amount = Math.round(Number(value))
  return `${amount > 0 ? '+' : amount < 0 ? '−' : ''}${number(Math.abs(amount))}원`
}

export function dateTime(value) {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  return date.toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
