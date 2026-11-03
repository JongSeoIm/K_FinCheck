'use client'

import useApi from '../hooks/useApi'
import { PanelEmpty, PanelError, PanelLoading } from './PanelState'

// 원 → "12.3조" / "4,567억"
function formatKrw(value) {
  if (value == null) return '—'
  const abs = Math.abs(value)
  const sign = value < 0 ? '−' : ''
  if (abs >= 1e12) return `${sign}${(abs / 1e12).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}조`
  return `${sign}${Math.round(abs / 1e8).toLocaleString('ko-KR')}억`
}

// 전년 대비: 둘 다 흑자일 때만 증감률(%)을 계산하고, 흑자·적자가 바뀌면 문구로 표시합니다.
// (적자에서 흑자로 바뀐 경우 % 를 계산하면 +403% 처럼 의미 없는 숫자가 나오기 때문)
function compare(current, previous) {
  if (current == null || previous == null) return null
  if (previous < 0 && current >= 0) return { text: '흑자 전환', tone: 'positive' }
  if (previous >= 0 && current < 0) return { text: '적자 전환', tone: 'negative' }
  if (previous < 0 && current < 0) return { text: '적자 지속', tone: 'negative' }
  if (previous === 0) return null
  const rate = ((current - previous) / previous) * 100
  return {
    text: `${rate > 0 ? '+' : ''}${rate.toFixed(1)}%`,
    tone: rate > 0 ? 'positive' : rate < 0 ? 'negative' : '',
  }
}

function Cell({ value, previous }) {
  const result = compare(value, previous)
  return (
    <td>
      {formatKrw(value)}
      {result && <small className={result.tone}>{result.text}</small>}
    </td>
  )
}

// 최근 실적: OpenDART 사업보고서 기준 최근 3개 연도 매출액 · 영업이익 · 당기순이익 (전년 대비 증감)
export default function EarningsPanel({ code }) {
  const { status, data, error, reload } = useApi(`/api/kis/financials/${code}`)

  if (status === 'loading') return <PanelLoading />
  if (status === 'error') {
    if (error?.code === 'DART_NOT_CONFIGURED') {
      return <PanelEmpty title='OpenDART 키가 설정되지 않았습니다.'>백엔드 application-local.properties 에 dart.api-key 를 넣어 주세요.</PanelEmpty>
    }
    if (error?.code === 'NO_FINANCIALS' || error?.code === 'NO_CORP_CODE') {
      return <PanelEmpty title='실적 데이터가 없습니다.'>{error.message}</PanelEmpty>
    }
    return <PanelError error={error} onRetry={reload} />
  }

  const rows = Array.isArray(data?.rows) ? data.rows : []
  if (rows.length === 0) return <PanelEmpty title='실적 데이터가 없습니다.' />

  return (
    <>
      <table className='financial-table'>
        <thead>
          <tr><th>연도</th><th>매출액</th><th>영업이익</th><th>순이익</th></tr>
        </thead>
        <tbody>
          {rows.map((row, index) => {
            const previous = rows[index + 1]
            return (
              <tr key={row.year}>
                <td>{row.year}</td>
                <Cell value={row.revenue} previous={previous?.revenue} />
                <Cell value={row.operatingProfit} previous={previous?.operatingProfit} />
                <Cell value={row.netIncome} previous={previous?.netIncome} />
              </tr>
            )
          })}
        </tbody>
      </table>
      <small className='panel-source'>출처: {data.source} ({data.reportYear}년, {data.basis} 기준) · 괄호 아래는 전년 대비</small>
    </>
  )
}