'use client'

import { useEffect, useRef, useState } from 'react'
import useStockData from '../hooks/useStockData'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const prices = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 0 })
const quoteTime = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul',
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })

function BrokerOpinion({ item, price }) {
  const difference = price != null && item.targetPrice > 0 ? (item.targetPrice / price - 1) * 100 : null
  const gap = difference == null ? null : Math.abs(difference).toFixed(1)
  return <li className='broker-opinion'>
    <div className='broker-opinion-heading'>
      <strong>{item.broker || '증권사 미제공'}</strong>
      <time dateTime={item.date}>{item.date.replaceAll('-', '.')} 발표</time>
    </div>
    <div className='broker-opinion-body'>
      <span>발표 의견 <strong>{item.opinion || '—'}</strong></span>
      <span>목표주가 <strong>{item.targetPrice == null ? '—' : `${prices.format(item.targetPrice)}원`}</strong></span>
    </div>
    {difference != null && <p className='broker-opinion-difference'>
      {gap === '0.0' ? '목표주가는 기준 주가와 비슷한 수준'
        : `목표주가는 기준 주가보다 ${gap}% ${difference > 0 ? '높게' : '낮게'} 제시`}
    </p>}
  </li>
}

function OpinionDistribution({ distribution }) {
  return <svg className='opinion-chart' viewBox='0 0 320 235' role='img'
    aria-label={distribution.map(item => `${item.label} ${item.percentage}% ${item.count}곳`).join(', ')}>
    {[0, 50, 100].map(value => <g key={value}>
      <line className='opinion-grid' x1='40' x2='310' y1={175 - value * 1.3} y2={175 - value * 1.3} />
      <text className='opinion-axis' x='32' y={180 - value * 1.3} textAnchor='end'>{value}%</text>
    </g>)}
    {distribution.map((item, index) => {
      const center = 85 + index * 90
      const top = 175 - item.percentage * 1.3
      return <g key={item.label}>
        <title>{item.label} {item.percentage}% · {item.count}곳</title>
        <rect className={`opinion-bar opinion-bar-${index}`} x={center - 22} y={top} width='44' height={175 - top} />
        <text className='opinion-value' x={center} y={top - 10} textAnchor='middle'>{item.percentage}%</text>
        <text className='opinion-label' x={center} y='198' textAnchor='middle'>{item.label}</text>
        <text className='opinion-axis' x={center} y='220' textAnchor='middle'>{item.count}곳</text>
      </g>
    })}
  </svg>
}

function BrokerOpinionList({ items, price }) {
  const listRef = useRef(null)
  const [moreBelow, setMoreBelow] = useState(false)
  const [moreAbove, setMoreAbove] = useState(false)

  useEffect(() => {
    const list = listRef.current
    let frame
    const updateFade = () => {
      setMoreBelow(list.scrollHeight - list.scrollTop - list.clientHeight > 2)
      setMoreAbove(list.scrollTop > 2)
    }
    const measure = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        const rows = [...list.children]
        // 3곳은 온전히 보여주고 다음 항목의 시작을 살짝 노출한다.
        list.style.maxHeight = rows.length > 3
          ? `${Math.ceil(rows.slice(0, 3).reduce((sum, row) => sum + row.getBoundingClientRect().height, 0)) + 36}px`
          : 'none'
        updateFade()
      })
    }
    const observer = new ResizeObserver(measure)
    observer.observe(list)
    for (const row of list.children) observer.observe(row)
    list.addEventListener('scroll', updateFade, { passive: true })
    measure()
    return () => {
      observer.disconnect()
      cancelAnimationFrame(frame)
      list.removeEventListener('scroll', updateFade)
    }
  }, [items])

  return <div className='broker-opinion-scroll'>
    <div className='broker-opinion-window'>
      <ul ref={listRef} className='broker-opinion-list' tabIndex={0} aria-label='증권사별 투자의견 목록'>
        {items.map(item => <BrokerOpinion key={item.broker} item={item} price={price} />)}
      </ul>
      {moreAbove && <div className='broker-opinion-fade broker-opinion-fade-top' aria-hidden='true' />}
      {moreBelow && <div className='broker-opinion-fade broker-opinion-fade-bottom' aria-hidden='true' />}
    </div>
  </div>
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

  return <div className='opinion-panel'>
    <p className='opinion-basis'>증권사 발표 당시 투자의견</p>
    <p className='opinion-meta'>최근 3개월 · 조회 {feed.items.length}곳</p>
    <div className='opinion-current-quote'>
      <span>비교 기준 주가</span><strong>{price == null ? '확인 불가' : `${prices.format(price)}원`}</strong>
      <small>KIS · {basisTime ? `${quote?.exchangeTime ? '최근 체결' : '마지막 조회'} ${basisTime} (한국시간)` : '기준 시각 확인 불가'}</small>
    </div>
    {feed.items.length === 0 ? <p className='opinion-message'>최근 의견이 없습니다.</p> :
      <BrokerOpinionList items={feed.items} price={price} />
    }
    <details className='opinion-details'>
      <summary>의견 분포 보기</summary>
      {feed.partial
        ? <p className='opinion-message'>일부 자료만 조회되어 비율 집계를 표시하지 않습니다.</p>
        : feed.countedBrokers > 0
          ? <><p className='opinion-footnote'>매수·중립·매도 분류 가능 {feed.countedBrokers}곳 기준</p><OpinionDistribution distribution={feed.distribution} /></>
          : <p className='opinion-message'>비율로 집계할 수 있는 의견이 없습니다.</p>}
      {(feed.excludedBrokers > 0 || feed.missingBrokerRows > 0) && <p className='opinion-footnote'>
        집계 제외: 의견 분류 불가 {feed.excludedBrokers}곳 · 증권사명 누락 {feed.missingBrokerRows}건
      </p>}
    </details>
    <p className='opinion-footnote opinion-fine-print'>KIS · {feed.periodStart.replaceAll('-', '.')} ~ {feed.periodEnd.replaceAll('-', '.')}<br />
      조회된 증권사별 최신 의견 1건 기준
    </p>
  </div>
}
