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

const labels = [
  ['strongBuy', '강력 매수', '#17643f'],
  ['buy', '매수', '#57a47a'],
  ['hold', '중립', '#999999'],
  ['sell', '매도', '#d98888'],
  ['strongSell', '강력 매도', '#b73a3a'],
]

function won(value) {
  return `${Number(value).toLocaleString('ko-KR')}원`
}

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
          {item.name}: {item.value}건
        </span>
      ))}
    </div>
  )
}

export default function RecommendationChart({ symbol }) {
  const [result, setResult] = useState({ symbol: null, data: [], target: null, reports: [], error: '' })

  useEffect(() => {
    const controller = new AbortController()

    fetch(`/api/kis/opinion/${encodeURIComponent(symbol)}`, { signal: controller.signal })
      .then(async (response) => {
        const body = await response.json().catch(() => null)
        if (!response.ok) throw new Error(body?.error || '투자의견을 불러오지 못했습니다.')
        setResult({ symbol, data: body.monthly || [], target: body.target || null, reports: body.reports || [], error: '' })
      })
      .catch((error) => {
        if (error.name !== 'AbortError') {
          setResult({ symbol, data: [], target: null, reports: [], error: error.message })
        }
      })

    return () => controller.abort()
  }, [symbol])

  const status = result.symbol !== symbol ? 'loading' : result.error ? 'error' : 'ready'
  const data = useMemo(() => (result.symbol === symbol ? result.data : []), [result, symbol])
  const target = result.symbol === symbol ? result.target : null
  const reports = result.symbol === symbol ? result.reports : []

  // 최근 몇 달 전체를 합산해서 요약합니다. (한 달에 리포트가 1~2건뿐일 수 있어서)
  const latest = useMemo(() => {
    if (data.length === 0) return null
    const sum = { strongBuy: 0, buy: 0, hold: 0, sell: 0, strongSell: 0 }
    for (const row of data) for (const [key] of labels) sum[key] += Number(row[key] || 0)
    return sum
  }, [data])
  const summary = useMemo(() => {
    if (!latest) return null
    const total = labels.reduce((sum, [key]) => sum + Number(latest[key] || 0), 0)
    const positive = Number(latest.strongBuy || 0) + Number(latest.buy || 0)
    return { total, score: total ? Math.round((positive / total) * 100) : 0 }
  }, [latest])

  if (status === 'loading') {
    return <div className='skeleton-list'><span className='skeleton-block' /><span className='skeleton-row compact' /></div>
  }
  if (status === 'error') return <p className='empty-copy'>{result.error}</p>
  if (!latest || !summary || summary.total === 0) return <p className='empty-copy'>최근 투자의견 리포트가 없습니다.</p>

  return (
    <div className='recommendation-summary'>
      <div className='consensus-score'>
        <div><strong>{summary.score}</strong><span>/ 100</span></div>
        <p>{summary.score >= 65 ? '매수 우세' : summary.score >= 45 ? '중립' : '매도 우세'}</p>
      </div>

      <p className='recommendation-caption'>최근 약 4개월 · 증권사 의견 {summary.total}건</p>
      <div className='sentiment-stack' aria-label='최신 투자의견 분포'>
        {labels.map(([key, label], index) => {
          const value = Number(latest[key] || 0)
          const percent = summary.total ? (value / summary.total) * 100 : 0
          return <span key={key} style={{ width: `${percent}%`, background: labels[index][2] }} title={`${label} ${value}건`} />
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

      {target && (
        <>
          <div className='trend-heading'>목표가</div>
          <ul className='sentiment-list'>
            <li>
              <span>평균 목표가 ({target.count}건)</span>
              <strong>{won(target.average)}</strong>
            </li>
            {target.upsidePercent != null && (
              <li>
                <span>현재가 대비</span>
                <strong className={target.upsidePercent >= 0 ? 'positive' : 'negative'}>
                  {target.upsidePercent >= 0 ? '▲' : '▼'} {Math.abs(target.upsidePercent).toFixed(1)}%
                </strong>
              </li>
            )}
            <li>
              <span>최저 ~ 최고</span>
              <strong>{won(target.min)} ~ {won(target.max)}</strong>
            </li>
          </ul>
        </>
      )}

      {reports.length > 0 && (
        <>
          <div className='trend-heading'>최근 리포트</div>
          <ul className='sentiment-list'>
            {reports.map((report) => (
              <li key={`${report.firm}-${report.date}-${report.target}`}>
                <span>{report.firm} · {report.date}</span>
                <strong>{report.target ? won(report.target) : '—'}</strong>
              </li>
            ))}
          </ul>
        </>
      )}

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
    </div>
  )
}
