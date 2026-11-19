'use client'

import { useEffect, useState } from 'react'
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'

const backendUrl = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const investors = [
  { key: 'individual', name: '개인', color: '#5a70ab' },
  { key: 'institution', name: '기관', color: '#a86625' },
  { key: 'foreign', name: '외국인', color: '#397c66' },
]
const quantity = new Intl.NumberFormat('ko-KR', { signDisplay: 'exceptZero' })
const units = new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 })
const shortDate = date => date.slice(5).replace('-', '.')
const shares = value => value == null ? '—' : `${quantity.format(value)}주`
const netClass = value => value > 0 ? 'positive' : value < 0 ? 'negative' : undefined

function InvestorTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return <div className='chart-tooltip'>
    <time dateTime={label}>{label}</time>
    {payload.map(entry => <div className='investor-tooltip-row' key={entry.dataKey}>
      <span>{entry.name}</span><strong className={netClass(entry.value)}>{shares(entry.value)}</strong>
    </div>)}
  </div>
}

export default function InvestorTradesPanel({ symbol }) {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState(null)

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch(`${backendUrl}/api/stocks/${encodeURIComponent(symbol)}/investor-trades`, { signal: controller.signal })
        if (!response.ok) throw new Error('Investor trades unavailable')
        const rows = await response.json()
        if (!Array.isArray(rows)) throw new Error('Invalid investor trades response')
        if (!controller.signal.aborted) setResult({ symbol, attempt, rows })
      } catch {
        if (!controller.signal.aborted) setResult({ symbol, attempt, error: true })
      }
    }
    load()
    return () => controller.abort()
  }, [symbol, attempt])

  const current = result?.symbol === symbol && result.attempt === attempt ? result : null
  if (!current) return <p className='financial-state' role='status'>투자자별 매매 동향을 불러오는 중입니다.</p>
  if (current.error) return <div className='financial-state' role='status'>
    <p>투자자별 매매 동향을 불러오지 못했습니다.</p>
    <button className='investor-retry' type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
  </div>
  if (!current.rows.length) return <p className='financial-state' role='status'>조회된 투자자별 매매 동향이 없습니다.</p>

  const { rows } = current
  return <div className='investor-trades'>
    <div className='investor-trades-heading'>
      <p className='financial-meta'>KIS · KRX · {rows.at(-1).date} 기준 · 순매수 수량</p>
      <ul className='investor-trades-legend' aria-label='그래프 범례'>
        {investors.map(({ key, name, color }) => <li key={key}><span style={{ background: color }} aria-hidden='true' />{name}</li>)}
      </ul>
    </div>
    <div className='investor-trades-content'>
      <div className='investor-trades-chart'>
        <ResponsiveContainer width='100%' height='100%'>
          <LineChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
            <CartesianGrid vertical={false} stroke='var(--line-light)' />
            <XAxis dataKey='date' tickFormatter={shortDate} tick={{ fontSize: 12, fontWeight: 400 }} />
            <YAxis orientation='right' width={72} tickFormatter={value => units.format(value)} tick={{ fontSize: 12, fontWeight: 400 }} />
            <ReferenceLine y={0} stroke='var(--muted)' ifOverflow='extendDomain' />
            <Tooltip content={<InvestorTooltip />} />
            {investors.map(({ key, name, color }) => <Line key={key} dataKey={key} name={name} stroke={color}
              strokeWidth={2} dot={{ r: 3 }} isAnimationActive={false} />)}
          </LineChart>
        </ResponsiveContainer>
      </div>
      <div className='investor-trades-table'>
        <table className='financial-table'>
          <caption className='financial-caption'>거래일별 순매수 수량</caption>
          <thead><tr><th scope='col'>거래일</th>{investors.map(({ key, name }) => <th key={key} scope='col'>{name}</th>)}</tr></thead>
          <tbody>{rows.toReversed().map(row => <tr key={row.date}>
            <th scope='row'><time dateTime={row.date}>{shortDate(row.date)}</time></th>
            {investors.map(({ key }) => <td key={key}>{shares(row[key])}</td>)}
          </tr>)}</tbody>
        </table>
      </div>
    </div>
  </div>
}
