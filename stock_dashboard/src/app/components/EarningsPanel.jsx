'use client'

import { Fragment, useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const amounts = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 })
const comparisons = { TURNAROUND: '흑자 전환', TURNED_LOSS: '적자 전환', CONTINUED_LOSS: '적자 지속' }

function formatAmount(value) {
  if (value == null) return '—'
  return amounts.format(Number(value) / 100000000)
}

function MetricComparison({ metric, report }) {
  const current = Number(metric.currentAmount)
  const previous = Number(metric.previousAmount)
  if (metric.currentAmount == null || metric.previousAmount == null
    || !Number.isFinite(current) || !Number.isFinite(previous)) return null

  const maximum = Math.max(Math.abs(current), Math.abs(previous))
  const hasNegative = current < 0 || previous < 0
  const baseline = hasNegative ? 50 : 0
  const scale = hasNegative ? 50 : 100
  const values = [
    { year: report.previousYear, value: previous, kind: 'previous' },
    { year: report.businessYear, value: current, kind: 'current' },
  ]

  return (
    <div className='financial-comparison' role='img'
      aria-label={`${metric.label}: ${report.previousYear}년 ${formatAmount(metric.previousAmount)}억 원, ${report.businessYear}년 ${formatAmount(metric.currentAmount)}억 원. 항목별 동일 축 비교.`}>
      {values.map(({ year, value, kind }) => {
        const width = maximum === 0 ? 0 : Math.abs(value) / maximum * scale
        return (
          <div className='financial-bar-row' key={year} aria-hidden='true'>
            <span>{year}</span>
            <div className='financial-bar-track'>
              <i className='financial-bar-baseline' style={{ left: `${baseline}%` }} />
              <i className={`financial-bar financial-bar-${kind}`} style={{
                left: `${value < 0 ? baseline - width : baseline}%`, width: `${width}%`,
              }} />
            </div>
          </div>
        )
      })}
    </div>
  )
}

function Growth({ metric }) {
  const comparable = metric.currentAmount != null && metric.previousAmount != null
  const difference = comparable ? Number(metric.currentAmount) - Number(metric.previousAmount) : 0
  const direction = difference > 0 ? 'positive' : difference < 0 ? 'negative' : ''
  const label = comparisons[metric.comparison] || (metric.growthRate == null ? '—'
    : `${metric.growthRate > 0 ? '+' : ''}${metric.growthRate.toFixed(1)}%`)
  return <span className={`financial-growth ${direction}`}>
    {label}
  </span>
}

export default function EarningsPanel({ symbol }) {
  const latestYear = Number(new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric' }).format(new Date())) - 1
  const [year, setYear] = useState(latestYear)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch(`${BACKEND_URL}/api/stocks/${encodeURIComponent(symbol)}/financials?year=${year}`, {
          signal: controller.signal,
        })
        const data = await response.json()
        if (!response.ok) throw new Error(data.error || '실적 자료를 불러오지 못했습니다.')
        if (controller.signal.aborted) return
        setError(null)
        setResult({ symbol, year, attempt, data })
      } catch (err) {
        if (!controller.signal.aborted) {
          setError({ symbol, year, attempt, message: err.name === 'TypeError' ? '실적 서버에 연결하지 못했습니다.' : err.message })
        }
      }
    }
    load()
    return () => controller.abort()
  }, [symbol, year, attempt])

  const currentError = error?.symbol === symbol && error.year === year && error.attempt === attempt ? error.message : null
  const report = result?.symbol === symbol && result.year === year && result.attempt === attempt ? result.data : null
  if (currentError) return (
    <div className='financial-state' role='status'>
      <p>{currentError}</p>
      <button type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
    </div>
  )
  if (!report) return <div className='financial-state' role='status'>실적 조회 중…</div>

  return (
    <div className='financial-panel'>
      <div className='financial-meta'>
        <select aria-label='실적 사업연도' value={year} onChange={event => setYear(Number(event.target.value))}>
          {[latestYear, latestYear - 1, latestYear - 2].map(value => <option key={value} value={value}>{value}년</option>)}
        </select> · 연간 · 연결 · 억 원
      </div>
      <table className='financial-table'>
        <caption className='financial-caption'>{report.businessYear}년 연결 연간 실적 및 전년 대비 증감률</caption>
        <thead><tr><th scope='col'>항목</th><th scope='col'>실적</th><th scope='col'>전년 대비</th></tr></thead>
        <tbody>{report.metrics.map(metric => (
          <Fragment key={metric.key}>
          <tr className='financial-value-row'>
            <th scope='row'>{metric.label}</th>
            <td title={metric.status !== 'AVAILABLE' ? '계정 자료 없음 또는 확인 필요' : undefined}>
              {formatAmount(metric.currentAmount)}
              <small>{report.previousYear}년 {formatAmount(metric.previousAmount)}</small>
            </td>
            <td><Growth metric={metric} /></td>
          </tr>
          <tr className='financial-chart-row'>
            <td colSpan={3}><MetricComparison metric={metric} report={report} /></td>
          </tr>
          </Fragment>
        ))}</tbody>
      </table>
      <div className='financial-source'>
        {report.sourceUrl && <a href={report.sourceUrl} target='_blank' rel='noopener noreferrer'>DART 사업보고서 ↗</a>}
        <span>— 자료 없음·비교 불가</span>
      </div>
    </div>
  )
}
