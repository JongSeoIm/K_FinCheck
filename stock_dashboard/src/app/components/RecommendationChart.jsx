'use client'

import { useEffect, useState } from 'react'
import { CartesianGrid, Line, LineChart, ReferenceArea, ReferenceDot, ResponsiveContainer, Tooltip, XAxis, YAxis, usePlotArea, useYAxisScale } from 'recharts'
import useStockCandles from '../hooks/useStockCandles'
import useStockData from '../hooks/useStockData'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const prices = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 })
const quoteTime = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul',
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })

function TargetLabels({ targets }) {
  const plot = usePlotArea()
  const scale = useYAxisScale()
  if (!plot || !scale) return null

  const labelGap = 18
  const edge = plot.x + plot.width
  let nextY = plot.y + 5
  const labels = []
  for (const [index, target] of [...targets].reverse().entries()) {
    const point = scale(target.value)
    const y = Math.min(Math.max(point, nextY),
      plot.y + plot.height - 5 - (targets.length - index - 1) * labelGap)
    nextY = y + labelGap
    labels.push(<g key={target.key} className='target-price-label'>
      {Math.abs(y - point) > 2 && <line x1={edge + 4} x2={edge + 12} y1={point} y2={y} stroke={target.color} />}
      <text x={edge + 15} y={y} dominantBaseline='central' fontSize={11} fill={target.color}>
        {target.label} {prices.format(target.value)}원
      </text>
    </g>)
  }
  return labels
}

function TargetPriceTooltip({ active, payload }) {
  const row = payload?.[0]?.payload
  if (!active || !row || row.close == null) return null
  return <div className='chart-tooltip'>
    {row.date ? <time dateTime={row.date}>{row.date}</time> : <span>비교 기준 주가</span>}
    <strong>{prices.format(row.close)}원</strong>
  </div>
}

function TargetPriceSummary({ symbol, items, price, businessDate }) {
  const { data: candles } = useStockCandles(symbol, 'D', 45)
  const values = items.map(item => item.targetPrice).filter(value => Number.isFinite(value) && value > 0)
  const average = values.reduce((sum, value) => sum + value, 0) / values.length
  const targets = [
    { key: 'low', label: '최저', value: Math.min(...values), color: '#1769aa' },
    { key: 'average', label: '평균', value: average, color: '#17643f' },
    { key: 'high', label: '최고', value: Math.max(...values), color: '#a43c3c' },
  ]
  const history = candles.filter(candle => Number.isFinite(candle.close) && candle.close > 0 && (!businessDate || candle.date < businessDate))
    .sort((first, second) => first.date.localeCompare(second.date))
  const basisIndex = history.length
  const targetIndex = basisIndex + Math.max(1, Math.round(basisIndex / 4))
  const chartData = [
    ...history.map((candle, index) => ({ index, date: candle.date, close: candle.close })),
    { index: basisIndex, close: price, low: price, average: price, high: price },
    { index: targetIndex, low: targets[0].value, average, high: targets[2].value },
  ]
  const axisLabels = {
    ...(history.length > 1 && { 0: history[0].date.slice(5).replace('-', '.') }),
    [basisIndex]: '기준 주가',
  }

  return <>
    <p className='opinion-basis'>최근 3개월 · 목표주가를 제시한 증권사 {values.length}곳</p>
    {price != null && <div className='target-price-chart' role='img' aria-label='비교 기준 주가와 증권사 목표주가 비교'>
      <ResponsiveContainer width='100%' height='100%'>
        <LineChart data={chartData} margin={{ top: 12, right: 118, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke='var(--line-light)' />
          <XAxis type='number' dataKey='index' domain={[0, targetIndex]}
            ticks={Object.keys(axisLabels).map(Number)} tickFormatter={value => axisLabels[value]} tick={{ fontSize: 11 }} />
          <YAxis domain={['auto', 'auto']} width={60} tickLine={false} axisLine={false}
            tickFormatter={value => prices.format(value)} tick={{ fontSize: 11 }} />
          <ReferenceArea x1={basisIndex} x2={targetIndex} fill='#e8edf4' fillOpacity={0.6} />
          <Tooltip content={<TargetPriceTooltip />} />
          <Line dataKey='close' stroke='#707783' strokeWidth={2} dot={false} isAnimationActive={false} />
          {targets.map(target => <Line key={target.key} dataKey={target.key} stroke={target.color}
            strokeWidth={2} strokeDasharray='4 4' activeDot={false}
            dot={({ cx, cy, payload }) => payload.index === targetIndex
              ? <circle cx={cx} cy={cy} r={4} fill={target.color} stroke='#fff' strokeWidth={1.5} /> : null}
            isAnimationActive={false} />)}
          <ReferenceDot x={basisIndex} y={price} r={4} fill='#354457' stroke='#fff' />
          <TargetLabels targets={targets} />
        </LineChart>
      </ResponsiveContainer>
    </div>}
    {price != null && <p className='opinion-footnote'>
      {history.length > 0 && '실선은 일봉 종가, '}점선은 기준 주가 대비 증권사 목표주가를 나타냅니다.
    </p>}
    <dl className='target-price-values'>
      {[targets[1], targets[2], targets[0]].map(target => {
        const difference = price == null ? null : (target.value / price - 1) * 100
        return <div key={target.key} className={target.key === 'average' ? 'target-price-average' : undefined}>
          <dt style={{ color: target.color }}>{target.label} 목표주가</dt>
          <dd><strong>{prices.format(target.value)}원</strong>
            {difference != null && <small>기준 주가 대비 {difference > 0 ? '+' : ''}{difference.toFixed(1)}%</small>}
          </dd>
        </div>
      })}
    </dl>
  </>
}

function BrokerOpinionList({ items }) {
  return <div className='broker-opinion-scroll' tabIndex={0} role='region' aria-label='증권사별 투자의견'>
    <table className='broker-opinion-table'>
      <thead><tr>
        <th scope='col'>증권사</th><th scope='col'>발표일</th>
        <th scope='col'>의견</th><th scope='col'>목표주가</th>
      </tr></thead>
      <tbody>{items.map(item => <tr key={item.broker}>
        <th scope='row'>{item.broker}</th>
        <td><time dateTime={item.date}>{item.date.replaceAll('-', '.')}</time></td>
        <td>{item.opinion || '—'}</td>
        <td>{item.targetPrice == null ? '—' : `${prices.format(item.targetPrice)}원`}</td>
      </tr>)}</tbody>
    </table>
  </div>
}

function OpinionDistribution({ distribution }) {
  return <ul className='opinion-ratios' aria-label='매수·중립·매도 의견 분포'>
    {distribution.map((item, index) => <li key={item.label}>
      <span>{item.label}</span>
      <span className='opinion-ratio-track' aria-hidden='true'>
        <span className={`opinion-bar-${index}`} style={{ width: `${item.percentage}%` }} />
      </span>
      <strong>{item.percentage}% · {item.count}곳</strong>
    </li>)}
  </ul>
}

export default function RecommendationChart({ symbol, liveQuote }) {
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const { data: restQuote } = useStockData(symbol, !liveQuote)
  const quote = liveQuote || restQuote
  const price = Number.isFinite(quote?.price) && quote.price > 0 ? quote.price : null
  const timestamp = quote?.exchangeTime || quote?.fetchedAt
  const date = timestamp ? new Date(timestamp) : null
  const basisTime = date && Number.isFinite(date.getTime()) ? quoteTime.format(date) : null

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch(`${BACKEND_URL}/api/stocks/${encodeURIComponent(symbol)}/opinions`, {
          signal: controller.signal,
        })
        const data = await response.json()
        if (!response.ok) throw new Error(data.error || '투자의견 자료를 불러오지 못했습니다.')
        if (!controller.signal.aborted) {
          setError(null)
          setResult({ symbol, attempt, data })
        }
      } catch (err) {
        if (!controller.signal.aborted) setError({ symbol, attempt,
          message: err instanceof TypeError ? '투자의견 서버에 연결하지 못했습니다.' : err.message })
      }
    }
    load()
    return () => controller.abort()
  }, [symbol, attempt])

  const currentError = error?.symbol === symbol && error.attempt === attempt ? error : null
  const feed = result?.symbol === symbol && result.attempt === attempt ? result.data : null

  if (currentError) return <div className='opinion-panel' role='status'>
    <p className='opinion-message'>{currentError.message}</p>
    <button type='button' className='opinion-retry' onClick={() => setAttempt(value => value + 1)}>다시 시도</button>
  </div>
  if (!feed) return <div className='opinion-panel' role='status'>투자의견을 불러오는 중입니다.</div>

  const hasTargets = feed.items.some(item => Number.isFinite(item.targetPrice) && item.targetPrice > 0)

  return <div className='opinion-panel'>
    {feed.partial
      ? <p className='opinion-message'>일부 자료만 조회되어 목표주가 집계를 표시하지 않습니다.</p>
      : hasTargets
        ? <TargetPriceSummary symbol={symbol} items={feed.items} price={price} businessDate={quote?.businessDate} />
        : <p className='opinion-message'>최근 3개월간 조회된 목표주가가 없습니다.</p>}
    <p className='opinion-footnote'>
      비교 기준 주가 {price == null ? '확인 불가' : `${prices.format(price)}원`}
      {basisTime && ` · ${quote?.exchangeTime ? '최근 체결' : '마지막 조회'} ${basisTime} (한국시간)`}
    </p>
    {feed.items.length === 0 ? <p className='opinion-message'>최근 의견이 없습니다.</p> : (
        <div className='opinion-detail-grid'>
          <BrokerOpinionList items={feed.items} />
          <div className='opinion-distribution'>
            <h3>투자의견 분포</h3>
            {feed.partial
              ? <p className='opinion-message'>일부 자료만 조회되어 비율 집계를 표시하지 않습니다.</p>
              : feed.countedBrokers > 0
                ? <>
                  <OpinionDistribution distribution={feed.distribution} />
                  <p className='opinion-footnote'>매수·중립·매도 분류 가능 {feed.countedBrokers}곳 기준</p>
                </>
                : <p className='opinion-message'>비율로 집계할 수 있는 의견이 없습니다.</p>}
            {(feed.excludedBrokers > 0 || feed.missingBrokerRows > 0) && <p className='opinion-footnote'>
              집계 제외: 의견 분류 불가 {feed.excludedBrokers}곳 · 증권사명 누락 {feed.missingBrokerRows}건
            </p>}
          </div>
        </div>
    )}
    <p className='opinion-footnote opinion-fine-print'>KIS · {feed.periodStart.replaceAll('-', '.')} ~ {feed.periodEnd.replaceAll('-', '.')}<br />
      조회된 증권사별 최신 의견 1건 기준
    </p>
  </div>
}
