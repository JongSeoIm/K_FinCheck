'use client'

import { useEffect, useState } from 'react'
import { stockApi } from '@/api/stockApi'
import { formatEok, formatNumber } from '@/app/lib/format'

const oneDecimal = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const formatMargin = (v) => (v == null ? '—' : `${oneDecimal.format(Number(v))}%`)

// 최근 실적 (우리 서버 /api/stocks/{symbol}/earnings → KIS 분기 손익계산서·재무비율)
// 분기 매출액·영업이익·영업이익률과 EPS
export default function EarningsPanel({ symbol }) {
  const [retry, setRetry] = useState(0)
  const [result, setResult] = useState({ key: null, data: null, error: '' })
  const requestKey = `${symbol}:${retry}`

  useEffect(() => {
    const controller = new AbortController()
    stockApi.getEarnings(symbol, { signal: controller.signal })
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
        <strong>실적을 불러오지 못했습니다.</strong>
        <p>{result.error}</p>
        <button type='button' onClick={() => setRetry((n) => n + 1)}>다시 시도</button>
      </div>
    )
  }

  const quarters = result.data?.quarters ?? []
  if (quarters.length === 0) {
    return <p className='empty-copy'>공시된 분기 실적이 없습니다.</p>
  }

  return (
    <div className='earnings'>
      <div className='earnings-list'>
        <div className='earnings-header'>
          <span>분기</span><span>매출액</span><span>영업이익</span><span>영업이익률</span>
        </div>
        {quarters.map((q) => (
          <div className='earnings-row' key={q.period}>
            <strong>{q.period}</strong>
            <span>{formatEok(q.revenue)}</span>
            <span>{formatEok(q.operatingProfit)}</span>
            <span className='earnings-margin'>{formatMargin(q.operatingMargin)}</span>
            <small className='earnings-sub'>EPS {q.eps == null ? '—' : `${formatNumber(q.eps)}원`}</small>
          </div>
        ))}
      </div>
      <small className='panel-note'>
        출처: 한국투자증권 분기 재무 · 단위 {result.data.unit} · 누적 공시값에서 분기 값을 계산
        {result.data.source === 'DEMO' && <span className='badge demo'>모의 데이터</span>}
      </small>
    </div>
  )
}
