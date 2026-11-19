export const eventLabels = {
  EARNINGS_RELEASE: '실적 발표',
  SHARE_BUYBACK: '자사주 공시',
  DIVIDEND: '배당 결정',
  DIVIDEND_RECORD_DATE: '배당기준일',
  DIVIDEND_PAYMENT: '배당금 지급 예정',
  ANNUAL_SHAREHOLDER_MEETING: '정기 주총',
  SPECIAL_SHAREHOLDER_MEETING: '임시 주총',
}

export function seoulDate() {
  return new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' })
}
