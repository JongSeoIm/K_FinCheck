'use client'

import { useEffect, useMemo, useState } from 'react'

// 금액(원) → 조/억 단위 문자열
function formatWon(value) {
  if (value == null) return '—'
  const abs = Math.abs(value)
  const sign = value < 0 ? '-' : ''
  if (abs >= 1e12) return `${sign}${(abs / 1e12).toFixed(1)}조`
  return `${sign}${Math.round(abs / 1e8).toLocaleString('ko-KR')}억`
}

function formatGrowth(value) {
  if (value == null) return { text: '—', className: '' }
  const up = value >= 0
  return {
    text: `${up ? '▲' : '▼'} ${Math.abs(value).toFixed(1)}%`,
    className: up ? 'positive' : 'negative',
  }
}

export default function EarningsPanel({ symbol }) {
  const [result, setResult] = useState({ symbol: null, items: [], error: '' })

  useEffect(() => {
    const controller = new AbortController()

    fetch(`/api/kis/earnings/${encodeURIComponent(symbol)}`, { signal: controller.signal })
      .then(async (response) => {
        const data = await response.json().catch(() => null)
        if (!response.ok) throw new Error(data?.error || '실적을 불러오지 못했습니다.')
        setResult({ symbol, items: data, error: '' })
      })
      .catch((error) => {
        if (error.name !== 'AbortError') {
          setResult({ symbol, items: [], error: error.message })
        }
      })

    return () => controller.abort()
  }, [symbol])

  const status = result.symbol !== symbol ? 'loading' : result.error ? 'error' : 'ready'
  const items = useMemo(() => (result.symbol === symbol ? result.items : []), [result, symbol])

  if (status === 'loading') {
    return <div className='skeleton-list'><span className='skeleton-row compact' /><span className='skeleton-row compact' /><span className='skeleton-row compact' /></div>
  }
  if (status === 'error') {
    return <p className='empty-copy'>{result.error}</p>
  }
  if (items.length === 0) {
    return <p className='empty-copy'>표시할 실적 공시가 없습니다.</p>
  }

  return (
    <div className='earnings-list'>
      <div className='earnings-header'>
        <span>기간</span><span>매출 / 영업이익</span><span>영업이익 전년비</span>
      </div>
      {items.slice(0, 4).map((item) => {
        const growth = formatGrowth(item.operatingYoy)
        return (
          <div className='earnings-row' key={item.period}>
            <strong>{item.period}</strong>
            <span>{formatWon(item.revenue)} / {formatWon(item.operatingProfit)}</span>
            <span className={growth.className}>{growth.text}</span>
          </div>
        )
      })}
      <small className='inline-warning'>출처: OpenDART 공시 (연결 기준, 전년 동기 대비)</small>
    </div>
  )
}
