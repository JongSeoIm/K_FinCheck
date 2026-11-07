'use client'

import { useEffect, useMemo, useState } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { stockApi } from '@/api/stockApi'
import useStockStore from '@/app/store/useStockStore'
import { formatClock, formatNumber } from '@/app/lib/format'

// 목업 RecommendationChart 와 같은 5단계·색상
const LABELS = [
  ['strongBuy', '강력 매수', '#17643f'],
  ['buy', '매수', '#57a47a'],
  ['hold', '중립', '#999999'],
  ['sell', '매도', '#d98888'],
  ['strongSell', '강력 매도', '#b73a3a'],
]

function formatPeriod(period) {
  const [, month] = String(period).split('-')
  return month ? `${Number(month)}월` : period
}

function OpinionTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return (
    <div className='opinion-tooltip'>
      <strong>{formatPeriod(label)} 투자의견</strong>
      {payload.map((item) => (
        <span key={item.dataKey}>
          <i style={{ background: item.color }} />
          {item.name}: {item.value}건
        </span>
      ))}
    </div>
  )
}

// 목표주가가 비교 기준 주가보다 얼마나 높은지
function targetGap(target, price) {
  if (target == null || !price) return null
  return ((Number(target) - Number(price)) / Number(price)) * 100
}

function gapText(gap) {
  if (gap == null) return ''
  if (Math.abs(gap) < 0.05) return '기준 주가와 비슷'
  return `기준 주가보다 ${Math.abs(gap).toFixed(1)}% ${gap > 0 ? '높음' : '낮음'}`
}

// 전문가 투자의견 (목업 배치: 점수 · 분포 · 5단계 목록 · 최근 월별 추세)
// 데이터: 우리 서버 /api/stocks/{symbol}/opinions → KIS 국내주식 종목투자의견
export default function OpinionPanel({ symbol }) {
  const [retry, setRetry] = useState(0)
  const [result, setResult] = useState({ key: null, opinion: null, error: '' })
  const quote = useStockStore((state) => state.quotes[symbol])
  const requestKey = `${symbol}:${retry}`

  useEffect(() => {
    const controller = new AbortController()
    stockApi.getOpinion(symbol, { signal: controller.signal })
      .then((opinion) => setResult({ key: requestKey, opinion, error: '' }))
      .catch((error) => {
        if (error.name !== 'AbortError') setResult({ key: requestKey, opinion: null, error: error.message })
      })
    return () => controller.abort()
  }, [symbol, requestKey])

  const opinion = result.opinion
  const summary = useMemo(() => {
    if (!opinion) return null
    const total = LABELS.reduce((sum, [key]) => sum + Number(opinion.latest[key] || 0), 0)
    return { total, score: opinion.score }
  }, [opinion])

  if (result.key !== requestKey) {
    return <div className='skeleton-list'><span className='skeleton-row' /><span className='skeleton-row' /></div>
  }

  if (result.error) {
    return (
      <div className='news-error' role='alert'>
        <strong>투자의견을 불러오지 못했습니다.</strong>
        <p>{result.error}</p>
        <button type='button' onClick={() => setRetry((n) => n + 1)}>다시 시도</button>
      </div>
    )
  }

  if (!opinion || !summary || opinion.brokerCount === 0) {
    return <p className='empty-copy'>투자의견 데이터가 없습니다.</p>
  }

  const { latest } = opinion

  return (
    <div className='opinion-summary'>
      <div className='consensus-score'>
        <div><strong>{summary.score ?? '—'}</strong><span>/ 100</span></div>
        <p>{summary.score == null ? '분류 불가' : summary.score >= 65 ? '매수 우세' : summary.score >= 45 ? '중립' : '매도 우세'}</p>
      </div>

      <p className='opinion-caption'>
        최근 집계 · 증권사 {opinion.brokerCount}곳
        {opinion.source === 'DEMO' && <span className='badge demo'>모의 데이터</span>}
      </p>
      <div className='sentiment-stack' aria-label='최신 투자의견 분포'>
        {LABELS.map(([key, label, color]) => {
          const value = Number(latest[key] || 0)
          const percent = summary.total ? (value / summary.total) * 100 : 0
          return <span key={key} style={{ width: `${percent}%`, background: color }} title={`${label} ${value}곳`} />
        })}
      </div>

      <ul className='sentiment-list'>
        {LABELS.map(([key, label, color]) => (
          <li key={key}>
            <span><i style={{ background: color }} />{label}</span>
            <strong>{latest[key] || 0}</strong>
          </li>
        ))}
        {latest.other > 0 && (
          <li title='분류 표에 없는 의견 문구 (점수 계산에서 제외)'>
            <span><i className='other' />기타</span>
            <strong>{latest.other}</strong>
          </li>
        )}
      </ul>

      {opinion.recent?.length > 0 && (
        <>
          <div className='trend-heading'>증권사별 발표 당시 의견</div>
          <div className='opinion-basis'>
            <span>비교 기준 주가</span>
            <strong>{quote?.price == null ? '확인 불가' : `${formatNumber(quote.price)}원`}</strong>
            {quote && <small>{quote.source} · {quote.eventTime ? `체결 ${formatClock(quote.eventTime)}` : `조회 ${formatClock(quote.receivedAt)}`}</small>}
          </div>
          <ul className='opinion-reports'>
            {opinion.recent.map((report) => (
              <li key={report.broker}>
                <span>{report.broker}</span>
                <span>{report.opinion}</span>
                <span>목표주가 {report.targetPrice == null ? '—' : `${formatNumber(report.targetPrice)}원`}</span>
                <small>{report.date} 발표</small>
                {report.targetPrice != null && quote?.price != null && (
                  <em>{gapText(targetGap(report.targetPrice, quote.price))}</em>
                )}
              </li>
            ))}
          </ul>
        </>
      )}

      <div className='trend-heading'>최근 월별 추세</div>
      <div className='opinion-chart' aria-label={`${symbol} 월별 투자의견 누적 막대 그래프`}>
        <ResponsiveContainer width='100%' height='100%'>
          <BarChart data={opinion.months} margin={{ top: 8, right: 0, bottom: 0, left: -28 }}>
            <CartesianGrid vertical={false} stroke='#e5e5e5' />
            <XAxis dataKey='period' tickFormatter={formatPeriod} tick={{ fontSize: 10, fill: '#666' }} />
            <YAxis tick={{ fontSize: 9, fill: '#777' }} allowDecimals={false} />
            <Tooltip content={<OpinionTooltip />} />
            {LABELS.map(([key, label, color]) => (
              <Bar key={key} dataKey={key} name={label} stackId='opinion' fill={color} isAnimationActive={false} />
            ))}
          </BarChart>
        </ResponsiveContainer>
      </div>
      <small className='panel-note'>
        출처: 한국투자증권 종목투자의견 · 조회 기간 {opinion.from} ~ {opinion.to} · 증권사별 최신 의견 1건 기준
      </small>
    </div>
  )
}
