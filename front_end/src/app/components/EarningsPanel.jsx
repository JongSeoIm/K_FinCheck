'use client'

import { useEffect, useState } from 'react'

const fallbackEarnings = [
  { period: '2026 Q2', actual: 1.42, estimate: 1.31, surprisePercent: 8.4 },
  { period: '2026 Q1', actual: 1.28, estimate: 1.24, surprisePercent: 3.2 },
  { period: '2025 Q4', actual: 1.19, estimate: 1.22, surprisePercent: -2.5 },
  { period: '2025 Q3', actual: 1.11, estimate: 1.05, surprisePercent: 5.7 },
]

function formatValue(value) {
  return value == null ? '—' : Number(value).toFixed(2)
}

export default function EarningsPanel({ symbol }) {
  const [earnings, setEarnings] = useState([])
  const [status, setStatus] = useState('loading')

  useEffect(() => {
    const controller = new AbortController()

    fetch(`/api/stock/${symbol}/earnings`, { signal: controller.signal })
      .then(async (response) => {
        const data = await response.json()
        if (!response.ok) throw new Error(data.error)
        setEarnings(data)
        setStatus('ready')
      })
      .catch((error) => {
        if (error.name !== 'AbortError') {
          setEarnings(fallbackEarnings)
          setStatus('fallback')
        }
      })

    return () => controller.abort()
  }, [symbol])

  if (status === 'loading') {
    return <div className='skeleton-list'><span className='skeleton-row compact' /><span className='skeleton-row compact' /><span className='skeleton-row compact' /></div>
  }

  return (
    <div className='earnings-list'>
      <div className='earnings-header'>
        <span>분기</span><span>실제 / 예상 EPS</span><span>결과</span>
      </div>
      {earnings.slice(0, 4).map((item) => {
        const surprise = Number(item.surprisePercent ?? 0)
        return (
          <div className='earnings-row' key={`${item.period}-${item.quarter || ''}`}>
            <strong>{item.period}</strong>
            <span>{formatValue(item.actual)} / {formatValue(item.estimate)}</span>
            <span className={`surprise-badge ${surprise >= 0 ? 'beat' : 'miss'}`}>
              {surprise >= 0 ? '상회' : '하회'} {Math.abs(surprise).toFixed(1)}%
            </span>
          </div>
        )
      })}
      {status === 'fallback' && <small className='inline-warning'>API 지연으로 예시 실적을 표시합니다.</small>}
    </div>
  )
}
