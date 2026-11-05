'use client'

import { useMemo } from 'react'
import useApi from '../hooks/useApi'
import { PanelEmpty, PanelError, PanelLoading } from './PanelState'
import { won } from '@/app/lib/format'


// 증권사 의견 문구를 매수 / 중립 / 매도 로 묶습니다 (예: "BUY", "매수", "Outperform", "Trading Buy")
function classify(opinion) {
  const text = String(opinion || '').toUpperCase()
  if (/매도|SELL|UNDERPERFORM|REDUCE/.test(text)) return 'sell'
  if (/중립|HOLD|NEUTRAL|MARKETPERFORM|MARKET PERFORM/.test(text)) return 'hold'
  if (/매수|BUY|OUTPERFORM|OVERWEIGHT/.test(text)) return 'buy'
  return 'other'
}

// 증권사 투자의견 (KIS 국내주식 종목투자의견, 최근 6개월)
export default function InvestOpinionPanel({ code }) {
  const { status, data, error, reload } = useApi(`/api/kis/opinion/${code}`)
  const rows = useMemo(() => (Array.isArray(data) ? data : []), [data])

  // 증권사별 가장 최근 의견만 집계
  const summary = useMemo(() => {
    const latestByFirm = new Map()
    rows.forEach((row) => {
      if (!latestByFirm.has(row.firm)) latestByFirm.set(row.firm, row)
    })
    const latest = [...latestByFirm.values()]
    const counts = { buy: 0, hold: 0, sell: 0, other: 0 }
    latest.forEach((row) => { counts[classify(row.opinion)] += 1 })
    const targets = latest.map((row) => Number(row.targetPrice)).filter((value) => value > 0)
    const averageTarget = targets.length ? targets.reduce((a, b) => a + b, 0) / targets.length : null
    return { firms: latest.length, counts, averageTarget }
  }, [rows])

  if (status === 'loading') return <PanelLoading />
  if (status === 'error') return <PanelError error={error} onRetry={reload} />
  if (rows.length === 0) return <PanelEmpty title='최근 6개월 투자의견이 없습니다.' />

  return (
    <>
      <div className='opinion-summary'>
        <div><span>증권사</span><strong>{summary.firms}곳</strong></div>
        <div>
          <span>매수 · 중립 · 매도</span>
          <strong>
            <b className='positive'>{summary.counts.buy}</b> · {summary.counts.hold} · <b className='negative'>{summary.counts.sell}</b>
          </strong>
        </div>
        <div><span>평균 목표가</span><strong>{summary.averageTarget ? won(summary.averageTarget) : '—'}</strong></div>
      </div>

      <ul className='opinion-list'>
        {rows.slice(0, 6).map((row, index) => (
          <li key={`${row.date}-${row.firm}-${index}`}>
            <span>
              <strong>{row.firm || '—'}</strong> {row.opinion || '—'}
              {row.previousOpinion && row.previousOpinion !== row.opinion && <small> (이전 {row.previousOpinion})</small>}
            </span>
            <span className='target'>{row.targetPrice ? won(row.targetPrice) : '—'}</span>
            <small>{row.date}</small>
          </li>
        ))}
      </ul>
      <small className='panel-source'>출처: 한국투자증권 Open API · 증권사별 최근 의견 기준 집계</small>
    </>
  )
}
