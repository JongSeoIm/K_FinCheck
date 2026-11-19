'use client'

import { useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const won = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 4 })

export default function DividendPanel({ symbol }) {
  const [result, setResult] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const requestKey = `${symbol}:${attempt}`

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch(`${BACKEND_URL}/api/stocks/${encodeURIComponent(symbol)}/dividends`, {
          signal: controller.signal,
        })
        if (!response.ok) throw new Error()
        const feed = await response.json()
        if (!controller.signal.aborted) setResult({ requestKey, feed })
      } catch {
        if (!controller.signal.aborted) setResult({ requestKey, error: true })
      }
    }
    load()
    return () => controller.abort()
  }, [symbol, requestKey])

  if (result?.requestKey !== requestKey) return <div className='financial-state' role='status'>배당 내역 조회 중…</div>
  if (result.error) return <div className='financial-state' role='status'>
    <p>배당 내역을 불러오지 못했습니다.</p>
    <button type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
  </div>

  const { dividends, from, to } = result.feed
  const recent = dividends.slice(0, 6).reverse()
  const maximum = Math.max(...recent.map(entry => entry.cashPerShare))
  return <div className='financial-panel'>
    <p className='financial-meta'>보통주 · 배당기준일 기준 {from} ~ {to}</p>
    {dividends.length === 0 ? <p role='status'>조회 기간에 제공된 현금배당 내역이 없습니다.</p> : <>
      <div className='dividend-bars' aria-hidden='true'>
        {recent.map(entry => <div className='dividend-bar-column' key={dividendKey(entry)}>
          <div className='dividend-bar-track'>
            <div className='dividend-bar' style={{ height: `${entry.cashPerShare / maximum * 100}%` }}>
              <span>{won.format(entry.cashPerShare)}원</span>
            </div>
          </div>
          <span className='dividend-bar-date'>{entry.recordDate.slice(2).replaceAll('-', '.')}</span>
        </div>)}
      </div>
      <div className='dividend-table-wrap' role='region' aria-label='배당 내역 표' tabIndex={dividends.length > 3 ? 0 : undefined}>
        <table className='financial-table'>
          <caption className='financial-caption'>보통주 현금배당 내역</caption>
          <thead><tr>
            <th scope='col'>배당기준일</th><th scope='col'>구분</th>
            <th scope='col'>주당 배당금</th><th scope='col'>지급일</th>
          </tr></thead>
          <tbody>{dividends.map(entry => <tr key={dividendKey(entry)}>
            <th scope='row'>{entry.recordDate}</th>
            <td>{entry.kind || '—'}</td>
            <td>{won.format(entry.cashPerShare)}원</td>
            <td>{entry.paymentDate ? `${entry.paymentDate}${entry.paymentDate > to ? ' (예정)' : ''}` : '—'}</td>
          </tr>)}</tbody>
        </table>
      </div>
    </>}
    <div className='financial-source dividend-source'><span>KIS · 한국예탁결제원</span><span>— 자료 없음</span></div>
  </div>
}

function dividendKey({ recordDate, kind, cashPerShare, paymentDate }) {
  return `${recordDate}:${kind}:${cashPerShare}:${paymentDate}`
}
