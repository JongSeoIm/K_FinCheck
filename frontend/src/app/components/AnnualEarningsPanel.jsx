'use client'

import { useEffect, useState } from 'react'
import { stockApi } from '@/api/stockApi'
import { directionClass, formatRate } from '@/app/lib/format'

const eok = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const percent = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const formatEok = (v) => (v == null ? '—' : eok.format(v / 100_000_000))

const COMPARE_LABELS = {
  TURN_PROFIT: '흑자 전환',
  TURN_LOSS: '적자 전환',
  LOSS_CONTINUE: '적자 지속',
  NOT_COMPARABLE: '비교 불가',
}

function CompareBar({ year, value, max, current }) {
  const width = value == null || !max ? 0 : Math.max(1, (Math.abs(value) / max) * 100)
  return (
    <div className='annual-bar'>
      <span>{year}</span>
      <i><b className={current ? 'current' : ''} style={{ width: `${width}%` }} /></i>
    </div>
  )
}

function AnnualRow({ item, year }) {
  const max = Math.max(Math.abs(item.current ?? 0), Math.abs(item.previous ?? 0))
  return (
    <li className='annual-row'>
      <div className='annual-row-head'>
        <strong>{item.name}</strong>
        <span>{formatEok(item.current)}</span>
        <span className={item.compareStatus === 'NORMAL' ? `annual-change ${directionClass(item.changeRate)}` : 'annual-change'}>
          {item.compareStatus === 'NORMAL' ? formatRate(item.changeRate) : COMPARE_LABELS[item.compareStatus]}
        </span>
      </div>
      <small>{Number(year) - 1}년 {formatEok(item.previous)}</small>
      <CompareBar year={Number(year) - 1} value={item.previous} max={max} />
      <CompareBar year={year} value={item.current} max={max} current />
    </li>
  )
}

function metricText(metric) {
  if (metric.status === 'OK') return `${percent.format(metric.value)}%`
  return metric.status === 'MISSING' ? '자료 없음' : '계산 불가'
}

// 연간 실적·재무 지표 (우리 서버 /api/companies/{symbol}/annual-earnings → OpenDART 사업보고서)
export default function AnnualEarningsPanel({ symbol }) {
  const [retry, setRetry] = useState(0)
  const [result, setResult] = useState({ key: null, data: null, error: '' })
  const requestKey = `${symbol}:${retry}`

  useEffect(() => {
    const controller = new AbortController()
    stockApi.getAnnualEarnings(symbol, { signal: controller.signal })
      .then((data) => setResult({ key: requestKey, data, error: '' }))
      .catch((error) => {
        if (error.name !== 'AbortError') setResult({ key: requestKey, data: null, error: error.message })
      })
    return () => controller.abort()
  }, [symbol, requestKey])

  if (result.key !== requestKey) {
    return <div className='skeleton-list'><span className='skeleton-row' /><span className='skeleton-row' /></div>
  }

  if (result.error) {
    return (
      <div className='news-error' role='alert'>
        <strong>연간 실적을 불러오지 못했습니다.</strong>
        <p>{result.error}</p>
        <button type='button' onClick={() => setRetry((n) => n + 1)}>다시 시도</button>
      </div>
    )
  }

  const { data } = result
  return (
    <div className='annual'>
      <p className='annual-basis'>{data.bsnsYear}년 · {data.reportName} · {data.fsName} · 억 원</p>
      <ul className='annual-list'>
        {data.items.map((item) => <AnnualRow key={item.code} item={item} year={data.bsnsYear} />)}
      </ul>
      <dl className='annual-metrics'>
        {data.metrics.map((metric) => (
          <div key={metric.code}>
            <dt>{metric.name}</dt>
            <dd className={metric.status === 'OK' ? '' : 'annual-missing'}>{metricText(metric)}</dd>
          </div>
        ))}
      </dl>
      <small className='panel-note'>
        출처: {data.source} · <a href={data.url} target='_blank' rel='noopener noreferrer'>DART 사업보고서 원문 ↗</a>
      </small>
    </div>
  )
}
