'use client'

import { useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const amounts = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 })
const comparisons = { TURNAROUND: '흑자 전환', TURNED_LOSS: '적자 전환', CONTINUED_LOSS: '적자 지속' }

function formatAmount(value) {
  if (value == null) return '—'
  return amounts.format(Number(value) / 100000000)
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

function EstimateTable({ feed }) {
  const { estimates, estimateDate } = feed
  if (!estimates.length) return <p role='status'>KIS에서 제공된 예상 실적이 없습니다.</p>
  const metrics = [['revenue', '매출액'], ['operatingProfit', '영업이익'], ['netIncome', '당기순이익']]
  return <>
    <p className='financial-meta'>연간 추정치 · 억 원</p>
    <div className='financial-table-scroll'>
      <table className='financial-table'>
        <caption className='financial-caption'>KIS 결산월별 연간 예상 실적</caption>
        <thead><tr><th scope='col'>항목</th>
          {estimates.map(({ closingMonth }) => <th key={closingMonth} scope='col'>{closingMonth} 추정</th>)}
        </tr></thead>
        <tbody>{metrics.map(([key, label]) => <tr key={key}>
          <th scope='row'>{label}</th>
          {estimates.map(row => <td key={row.closingMonth}>{row[key] == null ? '—' : amounts.format(row[key])}</td>)}
        </tr>)}</tbody>
      </table>
    </div>
    <div className='financial-source'>
      <span>KIS · {estimateDate ? `추정 기준일 ${estimateDate}` : '추정 기준일 미제공'}</span>
      <span>— 자료 없음</span>
    </div>
  </>
}

export default function EarningsPanel({ symbol }) {
  const [mode, setMode] = useState('actual')
  return <>
    <div className='financial-modes' role='group' aria-label='실적 유형'>
      <button type='button' aria-pressed={mode === 'actual'} onClick={() => setMode('actual')}>발표 실적</button>
      <button type='button' aria-pressed={mode === 'estimate'} onClick={() => setMode('estimate')}>예상 실적</button>
    </div>
    <EarningsReport symbol={symbol} estimated={mode === 'estimate'} />
  </>
}

function EarningsReport({ symbol, estimated }) {
  const dateParts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit',
  }).formatToParts(new Date())
  const currentYear = Number(dateParts.find(part => part.type === 'year').value)
  const currentMonth = Number(dateParts.find(part => part.type === 'month').value)
  // 정기보고서 제출 시기를 지나고 나서 새 분기를 기본으로 선택한다.
  const defaultQuarter = Math.max(0, Math.floor((currentMonth - 3) / 3))
  const latestYear = currentYear - 1
  const [year, setYear] = useState(latestYear)
  const [period, setPeriod] = useState('annual')
  const [quarterYear, setQuarterYear] = useState(defaultQuarter ? currentYear : latestYear)
  const [quarter, setQuarter] = useState(defaultQuarter || 3)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const quarterly = period === 'quarterly'
  const endpoint = estimated ? 'financial-estimates'
    : quarterly ? `financials?year=${quarterYear}&quarter=${quarter}` : `financials?year=${year}`
  const requestKey = `${symbol}:${endpoint}:${attempt}`

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch(`${BACKEND_URL}/api/stocks/${encodeURIComponent(symbol)}/${endpoint}`, {
          signal: controller.signal,
        })
        const data = await response.json()
        if (!response.ok && data.code !== 'NO_REPORT') throw new Error(data.error || '실적 자료를 불러오지 못했습니다.')
        if (controller.signal.aborted) return
        setError(null)
        setResult({ requestKey, data })
      } catch (err) {
        if (!controller.signal.aborted) {
          setError({ requestKey, message: err.name === 'TypeError' ? '실적 서버에 연결하지 못했습니다.' : err.message })
        }
      }
    }
    load()
    return () => controller.abort()
  }, [symbol, endpoint, requestKey])

  const currentError = error?.requestKey === requestKey ? error.message : null
  const report = result?.requestKey === requestKey ? result.data : null
  return (
    <div className='financial-panel'>
      {!estimated && <div className='financial-meta'>
        <select aria-label='실적 집계 기간' value={period} onChange={event => setPeriod(event.target.value)}>
          <option value='annual'>연간</option>
          <option value='quarterly'>분기</option>
        </select>
        {' · '}
        <select aria-label='실적 사업연도' value={quarterly ? quarterYear : year}
          onChange={event => (quarterly ? setQuarterYear : setYear)(Number(event.target.value))}>
          {(quarterly ? [currentYear, latestYear, latestYear - 1, latestYear - 2] : [latestYear, latestYear - 1, latestYear - 2])
            .map(value => <option key={value} value={value}>{value}년</option>)}
        </select>
        {quarterly && <> · <select aria-label='실적 분기' value={quarter}
          onChange={event => setQuarter(Number(event.target.value))}>
          {[1, 2, 3].map(value => <option key={value} value={value}>{value}분기</option>)}
        </select></>}
        {' · 연결 · 억 원'}
      </div>}
      {currentError ? <div className='financial-state' role='status'>
        <p>{currentError}</p>
        <button type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
      </div> : !report ? <div className='financial-state' role='status'>실적 조회 중…</div>
        : report.code === 'NO_REPORT' ? <p role='status'>{report.error}</p>
          : estimated ? <EstimateTable feed={report} /> : <ActualTable report={report} quarter={quarterly ? quarter : null} />}
    </div>
  )
}

function ActualTable({ report, quarter }) {
  const period = quarter ? ` ${quarter}분기` : ''
  return <>
    <div className='financial-table-scroll'>
      <table className='financial-table'>
        <caption className='financial-caption'>{report.businessYear}년{period} 연결 실적 및 전년 대비 증감률</caption>
        <thead><tr>
          <th scope='col'>항목</th><th scope='col'>{report.previousYear}년{period}</th>
          <th scope='col'>{report.businessYear}년{period}</th><th scope='col'>{quarter ? '전년 동기 대비' : '전년 대비'}</th>
        </tr></thead>
        <tbody>{report.metrics.map(metric => (
          <tr key={metric.key}>
            <th scope='row'>{metric.label}</th>
            <td>{formatAmount(metric.previousAmount)}</td>
            <td title={metric.status !== 'AVAILABLE' ? '계정 자료 없음 또는 확인 필요' : undefined}>
              {formatAmount(metric.currentAmount)}
            </td>
            <td><Growth metric={metric} /></td>
          </tr>
        ))}</tbody>
      </table>
    </div>
      <div className='financial-source'>
        {report.sourceUrl && <a href={report.sourceUrl} target='_blank' rel='noopener noreferrer'>DART {quarter === 2 ? '반기보고서' : quarter ? '분기보고서' : '사업보고서'} ↗</a>}
        <span>— 자료 없음·비교 불가</span>
      </div>
  </>
}
