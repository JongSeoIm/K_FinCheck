'use client'

import { useEffect, useState } from 'react'

// 금액(원) → 억 원 단위 문자열. 값이 없으면 0이 아니라 '—'
function formatEok(value) {
  if (value == null) return '—'
  return `${Math.round(value / 1e8).toLocaleString('ko-KR')}억 원`
}

// 증감 상태별 표기 (상승 빨강 · 하락 파랑)
function describeChange(line) {
  switch (line.status) {
    case 'UP':
      return { text: `▲ ${line.changePercent.toFixed(1)}%`, className: 'positive' }
    case 'DOWN':
      return { text: `▼ ${Math.abs(line.changePercent).toFixed(1)}%`, className: 'negative' }
    case 'FLAT':
      return { text: '0.0%', className: '' }
    case 'TURN_PROFIT':
      return { text: '흑자전환', className: 'positive' }
    case 'TURN_LOSS':
      return { text: '적자전환', className: 'negative' }
    case 'DEFICIT':
      return { text: '적자 지속', className: 'negative' }
    default:
      return { text: '비교 불가', className: '' }
  }
}

function CompareBars({ current, previous }) {
  const max = Math.max(Math.abs(current ?? 0), Math.abs(previous ?? 0))
  const width = (value) => (value == null || max === 0 ? 0 : (Math.abs(value) / max) * 100)
  const bar = (value, color) => (
    <div style={{ height: 6, background: '#eee' }}>
      <div style={{ height: '100%', width: `${width(value)}%`, background: color }} />
    </div>
  )
  return (
    <div style={{ display: 'grid', gap: 2, marginTop: 4 }} aria-hidden='true'>
      {bar(current, '#444')}
      {bar(previous, '#bbb')}
    </div>
  )
}

export default function EarningsPanel({ symbol }) {
  const [result, setResult] = useState({ symbol: null, annual: null, error: '' })

  useEffect(() => {
    const controller = new AbortController()

    fetch(`/api/kis/earnings/${encodeURIComponent(symbol)}`, { signal: controller.signal })
      .then(async (response) => {
        const data = await response.json().catch(() => null)
        if (!response.ok) throw new Error(data?.error || '실적을 불러오지 못했습니다.')
        setResult({ symbol, annual: data, error: '' })
      })
      .catch((error) => {
        if (error.name !== 'AbortError') {
          setResult({ symbol, annual: null, error: error.message })
        }
      })

    return () => controller.abort()
  }, [symbol])

  const status = result.symbol !== symbol ? 'loading' : result.error ? 'error' : 'ready'
  const annual = status === 'ready' ? result.annual : null

  if (status === 'loading') {
    return <div className='skeleton-list'><span className='skeleton-row compact' /><span className='skeleton-row compact' /><span className='skeleton-row compact' /></div>
  }
  if (status === 'error') {
    return <p className='empty-copy'>{result.error}</p>
  }
  if (!annual || !annual.lines?.length) {
    return <p className='empty-copy'>표시할 실적 공시가 없습니다.</p>
  }

  return (
    <div className='earnings-list'>
      <small className='recommendation-caption'>
        {annual.businessYear}년 사업연도 · {annual.basis} 기준 · 단위: 억 원 (전기 {annual.previousYear}년 대비)
      </small>
      {annual.lines.map((line) => {
        const change = describeChange(line)
        return (
          <div key={line.key} style={{ padding: '6px 0', borderBottom: '1px solid #eee' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
              <strong>{line.label}</strong>
              <span className={change.className}>{change.text}</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 12 }}>
              <span>당기 {formatEok(line.current)}</span>
              <span>전기 {formatEok(line.previous)}</span>
            </div>
            <CompareBars current={line.current} previous={line.previous} />
          </div>
        )
      })}
      <small className='inline-warning'>
        출처: OpenDART 사업보고서
        {annual.dartUrl && (
          <>
            {' · '}
            <a href={annual.dartUrl} target='_blank' rel='noopener noreferrer'>원본 공시 보기</a>
          </>
        )}
      </small>
    </div>
  )
}
