'use client'

import { useEffect, useState } from 'react'
import { marketStocks } from '../lib/market'

const backendUrl = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const stocks = new Map(marketStocks.map(stock => [stock.symbol, stock]))
const investors = [
  ['foreignMillions', '외국인'],
  ['institutionMillions', '기관'],
  ['individualMillions', '개인'],
]
const amountFormat = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 })

export default function InvestorRanking({ onSelectStock }) {
  const [side, setSide] = useState(1)
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState(null)

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch(`${backendUrl}/api/stocks/investor-ranking`, { signal: controller.signal })
        if (!response.ok) throw new Error('Investor ranking unavailable')
        const data = await response.json()
        if (!Array.isArray(data?.stocks)) throw new Error('Invalid ranking response')
        if (!controller.signal.aborted) setResult({ attempt, data })
      } catch {
        if (!controller.signal.aborted) setResult({ attempt, error: true })
      }
    }
    load()
    return () => controller.abort()
  }, [attempt])

  const current = result?.attempt === attempt ? result : null
  const { date, stocks: rows } = current?.data ?? {}

  return <section className='market-table-panel investor-ranking' aria-label='투자자별 순위'>
    <div className='investor-ranking-header'>
      <div className='market-tabs' role='group' aria-label='순위 구분'>
        <button className={side === 1 ? 'active' : ''} type='button' aria-pressed={side === 1} onClick={() => setSide(1)}>순매수</button>
        <button className={side === -1 ? 'active' : ''} type='button' aria-pressed={side === -1} onClick={() => setSide(-1)}>순매도</button>
      </div>
      <p className='financial-meta'>KIS · KRX · 등록 {marketStocks.length}종목{date && <> · <time dateTime={date}>{date}</time> 기준</>}</p>
    </div>
    {!current && <p className='financial-state' role='status'>투자자별 순위를 불러오는 중입니다.</p>}
    {current?.error && <div className='financial-state' role='status'>
      <p>투자자별 순위를 불러오지 못했습니다.</p>
      <button className='investor-retry' type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
    </div>}
    {current?.data && !date && <p className='financial-state' role='status'>등록 종목을 같은 거래일로 비교할 수 있는 자료가 없습니다.</p>}
    {date && <div className='investor-ranking-groups'>
      {investors.map(([field, name]) => {
        const ranking = rows.filter(row => stocks.has(row.symbol) && Number.isFinite(row[field]) && row[field] * side > 0)
          .toSorted((a, b) => side * (b[field] - a[field]) || a.symbol.localeCompare(b.symbol))
        return <table className='market-table investor-ranking-table' key={field}>
          <caption className='sr-only'>{name} {side === 1 ? '순매수' : '순매도'} 순위</caption>
          <thead><tr>
            <th scope='col' className='investor-ranking-position'>순위</th>
            <th scope='col'>{name}</th>
            <th scope='col' className='market-number'>{side === 1 ? '순매수 대금' : '순매도 대금'}</th>
          </tr></thead>
          <tbody>{ranking.length ? ranking.map((row, index) => <tr key={row.symbol}>
            <td className='investor-ranking-position'>{index + 1}</td>
            <th scope='row'><button className='market-stock-name' type='button' onClick={() => onSelectStock(stocks.get(row.symbol))}>
              {stocks.get(row.symbol).name}
            </button></th>
            <td className='market-number'>{amountFormat.format(Math.abs(row[field]) / 100)}억원</td>
          </tr>) : <tr><td colSpan={3} className='investor-ranking-empty'>해당 종목이 없습니다.</td></tr>}</tbody>
        </table>
      })}
    </div>}
  </section>
}
