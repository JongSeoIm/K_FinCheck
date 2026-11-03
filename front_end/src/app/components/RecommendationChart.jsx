'use client'

import { useEffect, useMemo, useState } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

const fallback = [
  { period: '2026-04-01', strongBuy: 10, buy: 17, hold: 11, sell: 4, strongSell: 1 },
  { period: '2026-05-01', strongBuy: 11, buy: 18, hold: 10, sell: 3, strongSell: 1 },
  { period: '2026-06-01', strongBuy: 12, buy: 18, hold: 9, sell: 3, strongSell: 1 },
]

const labels = [
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

function RecommendationTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return (
    <div className='recommendation-tooltip'>
      <strong>{formatPeriod(label)} 투자의견</strong>
      {payload.map((item) => (
        <span key={item.dataKey}>
          <i style={{ background: item.color }} />
          {item.name}: {item.value}명
        </span>
      ))}
    </div>
  )
}

export default function RecommendationChart({ symbol }) {
  const [data, setData] = useState([])
  const [status, setStatus] = useState('loading')

  useEffect(() => {
    const controller = new AbortController()

    fetch(`/api/stock/${symbol}/recommendation`, { signal: controller.signal })
      .then(async (response) => {
        const result = await response.json()
        if (!response.ok) throw new Error(result.error)
        setData(result)
        setStatus('ready')
      })
      .catch((error) => {
        if (error.name !== 'AbortError') {
          setData(fallback)
          setStatus('fallback')
        }
      })

    return () => controller.abort()
  }, [symbol])

  const latest = data.at(-1) || data[0]
  const summary = useMemo(() => {
    if (!latest) return null
    const total = labels.reduce((sum, [key]) => sum + Number(latest[key] || 0), 0)
    const positive = Number(latest.strongBuy || 0) + Number(latest.buy || 0)
    return { total, score: total ? Math.round((positive / total) * 100) : 0 }
  }, [latest])

  if (status === 'loading') {
    return <div className='skeleton-list'><span className='skeleton-block' /><span className='skeleton-row compact' /></div>
  }
  if (!latest || !summary) return <p className='empty-copy'>투자의견 데이터가 없습니다.</p>

  return (
    <div className='recommendation-summary'>
      <div className='consensus-score'>
        <div><strong>{summary.score}</strong><span>/ 100</span></div>
        <p>{summary.score >= 65 ? '매수 우세' : summary.score >= 45 ? '중립' : '매도 우세'}</p>
      </div>

      <p className='recommendation-caption'>최근 집계 · 분석가 {summary.total}명</p>
      <div className='sentiment-stack' aria-label='최신 투자의견 분포'>
        {labels.map(([key, label], index) => {
          const value = Number(latest[key] || 0)
          const percent = summary.total ? (value / summary.total) * 100 : 0
          return <span key={key} style={{ width: `${percent}%`, background: labels[index][2] }} title={`${label} ${value}명`} />
        })}
      </div>

      <ul className='sentiment-list'>
        {labels.map(([key, label, color]) => (
          <li key={key}>
            <span><i style={{ background: color }} />{label}</span>
            <strong>{latest[key] || 0}</strong>
          </li>
        ))}
      </ul>

      <div className='trend-heading'>최근 월별 추세</div>
      <div className='recommendation-chart' aria-label={`${symbol} 월별 투자의견 누적 막대 그래프`}>
        <ResponsiveContainer width='100%' height='100%'>
          <BarChart data={data} margin={{ top: 8, right: 0, bottom: 0, left: -28 }}>
            <CartesianGrid vertical={false} stroke='#e5e5e5' />
            <XAxis dataKey='period' tickFormatter={formatPeriod} tick={{ fontSize: 10, fill: '#666' }} />
            <YAxis tick={{ fontSize: 9, fill: '#777' }} allowDecimals={false} />
            <Tooltip content={<RecommendationTooltip />} />
            {labels.map(([key, label, color]) => (
              <Bar key={key} dataKey={key} name={label} stackId='recommendation' fill={color} isAnimationActive={false} />
            ))}
          </BarChart>
        </ResponsiveContainer>
      </div>

      {status === 'fallback' && <small className='inline-warning'>API 지연으로 예시 분포를 표시합니다.</small>}
    </div>
  )
}
