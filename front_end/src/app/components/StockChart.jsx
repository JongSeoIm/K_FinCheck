'use client'

import { Fragment, useEffect, useMemo, useState } from 'react'
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import useStockStore from '@/app/store/useStockStore'
import CandleChart from './CandleChart'

const ranges = [
  { value: '1D', label: '1일', description: '당일 · 1분봉 + 실시간 체결 누적' },
  { value: '1W', label: '1주', description: '최근 1주 · 일봉' },
  { value: '1M', label: '1개월', description: '최근 1개월 · 일봉' },
  { value: '1Y', label: '1년', description: '최근 1년 · 일봉' },
]

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')

// 브라우저(한국) 기준 현재 시각 "HH:MM", 오늘 날짜 "YYYY-MM-DD"
const nowMinute = () => {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
const todayString = () => new Date().toLocaleDateString('sv-SE')

// 기간의 기준 날짜: 1주=7일 전, 1개월=한 달 전, 1년=1년 전 (그날 또는 그 이전 마지막 거래일 종가가 시작값)
function periodStart(range) {
  const d = new Date()
  if (range === '1W') d.setDate(d.getDate() - 7)
  else if (range === '1M') d.setMonth(d.getMonth() - 1)
  else d.setFullYear(d.getFullYear() - 1)
  return d.toLocaleDateString('sv-SE')
}

function ChartTooltip({ active, payload, firstPrice }) {
  if (!active || !payload?.length) return null
  const point = payload[0].payload
  const value = Number(point.price)
  const changeRate = firstPrice ? ((value - firstPrice) / firstPrice) * 100 : 0

  return (
    <div className='chart-tooltip'>
      <span>{point.full}</span>
      <strong>{won(value)}</strong>
      <span className={changeRate >= 0 ? 'positive' : 'negative'}>
        {changeRate >= 0 ? '+' : ''}{changeRate.toFixed(2)}%
      </span>
    </div>
  )
}

function Message({ children }) {
  return (
    <div
      className='chart-area'
      style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '8px', color: '#666' }}
    >
      {children}
    </div>
  )
}

export default function StockChart({ symbol }) {
  const [range, setRange] = useState('1D')
  const [bars, setBars] = useState([])
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [errorMessage, setErrorMessage] = useState('')
  const [livePoints, setLivePoints] = useState({})
  const [reloadKey, setReloadKey] = useState(0)
  const [mode, setMode] = useState('line') // line | candle (캔들은 일봉 기간에서만)

  // 1) Spring(KIS)에서 분봉/일봉 불러오기 (1일은 30초마다 다시)
  useEffect(() => {
    const controller = new AbortController()
    let timer

    const load = async (first) => {
      try {
        if (first) setStatus('loading')
        const response = await fetch(`/api/kis/candles/${symbol}?range=${range}`, {
          signal: controller.signal,
          cache: 'no-store',
        })
        const json = await response.json()
        if (!response.ok || !Array.isArray(json)) {
          throw new Error(json.error || '차트 조회에 실패했습니다.')
        }
        setBars(json)
        setStatus('ready')
        setErrorMessage('')
      } catch (requestError) {
        if (requestError.name === 'AbortError') return
        setErrorMessage(requestError.message)
        if (first) setStatus('error') // 갱신 중 실패는 기존 차트를 그대로 둡니다.
      }
    }

    setBars([])
    setLivePoints({})
    load(true)
    if (range === '1D') timer = setInterval(() => load(false), 30000)

    return () => {
      controller.abort()
      clearInterval(timer)
    }
  }, [symbol, range, reloadKey])

  // 2) 현재가는 StockInfo가 5초마다 받아 둔 값을 같이 씁니다 (두 화면 값이 항상 같도록)
  const live = useStockStore((state) => state.prices[symbol])

  useEffect(() => {
    if (range !== '1D' || !live?.price) return
    const key = nowMinute()
    if (key < '09:00' || key > '15:30') return // 장 시간 밖은 누적하지 않음
    setLivePoints((current) => ({ ...current, [key]: Number(live.price) }))
  }, [live, range])

  // 일봉(1주·1개월·1년): 기준 날짜부터, 오늘 봉은 현재가로 맞춤
  const dailyBars = useMemo(() => {
    if (range === '1D') return []
    const target = periodStart(range)
    let startIndex = 0
    bars.forEach((bar, index) => {
      if (bar.date <= target) startIndex = index
    })
    const list = bars.slice(startIndex).map((bar) => ({ ...bar }))
    if (live?.price && list.length > 0 && list.at(-1).date === todayString()) {
      const price = Number(live.price)
      const last = list.at(-1)
      last.close = price
      last.high = Math.max(last.high, price)
      last.low = Math.min(last.low, price)
    }
    return list
  }, [bars, range, live])

  const points = useMemo(() => {
    if (range === '1D') {
      const map = new Map(
        bars.map((bar) => [bar.time, { label: bar.time, full: `${bar.date} ${bar.time}`, price: bar.close, high: bar.high, low: bar.low }])
      )
      const today = todayString()
      // 오늘 분봉이 있을 때만 실시간 값을 이어 붙입니다 (휴장일 방지).
      if (bars.length > 0 && bars[0].date === today) {
        Object.entries(livePoints).forEach(([key, price]) => {
          map.set(key, { label: key, full: `${today} ${key}`, price })
        })
      }
      return [...map.values()].sort((a, b) => a.label.localeCompare(b.label))
    }
    return dailyBars.map((bar) => ({
      label: bar.date.slice(5).replace('-', '/'),
      full: bar.date,
      price: bar.close,
      high: bar.high,
      low: bar.low,
    }))
  }, [bars, livePoints, range, dailyBars])

  const prices = points.map((point) => point.price)
  // 1일은 전일 종가 기준(StockInfo와 같은 등락률), 나머지는 기간 시작 종가 기준
  const firstPrice = range === '1D' && live?.prevClose ? Number(live.prevClose) : (prices[0] ?? 0)
  const lastPrice = prices.at(-1) ?? 0
  // 기간 고가/저가는 종가가 아니라 장중 고가·저가 기준
  const highs = points.map((point) => point.high || point.price)
  const lows = points.map((point) => point.low || point.price)
  const highPrice = highs.length ? Math.max(...highs) : 0
  const lowPrice = lows.length ? Math.min(...lows) : 0
  const rising = lastPrice >= firstPrice
  const changeRate = firstPrice ? ((lastPrice - firstPrice) / firstPrice) * 100 : 0
  const selectedRange = ranges.find((item) => item.value === range)
  const liveCount = Object.keys(livePoints).length
  const showCandle = mode === 'candle' && range !== '1D'

  return (
    <div className='chart-container'>
      <div className='chart-controls'>
        <div className='stream-mode' aria-label='차트 종류 선택'>
          <button
            type='button'
            className={!showCandle ? 'active' : ''}
            onClick={() => setMode('line')}
          >
            선
          </button>
          <span className='tab-separator' aria-hidden='true'>·</span>
          <button
            type='button'
            className={showCandle ? 'active' : ''}
            disabled={range === '1D'}
            title={range === '1D' ? '캔들은 1주·1개월·1년(일봉)에서 볼 수 있습니다' : undefined}
            onClick={() => setMode('candle')}
          >
            캔들
          </button>
        </div>

        <div className='range-tabs' aria-label='차트 기간 선택'>
          {ranges.map((item, index) => (
            <Fragment key={item.value}>
              {index > 0 && <span className='tab-separator' aria-hidden='true'>·</span>}
              <button
                type='button'
                className={range === item.value ? 'active' : ''}
                aria-pressed={range === item.value}
                onClick={() => setRange(item.value)}
              >
                {item.label}
              </button>
            </Fragment>
          ))}
        </div>
      </div>

      <div className='stream-status'>
        <span>{selectedRange.description}</span>
        <span>
          {status === 'error'
            ? '연결 오류'
            : range === '1D'
              ? liveCount > 0 ? '실시간 체결 반영 중 · 5초마다 갱신' : '장 마감 또는 체결 대기'
              : showCandle ? '일봉 캔들 (시가·고가·저가·종가)' : '일봉 종가'}
          {status === 'ready' && errorMessage ? ' · 갱신 지연' : ''}
        </span>
      </div>

      {status === 'loading' && <Message>차트를 불러오는 중…</Message>}

      {status === 'error' && (
        <Message>
          <strong>차트를 불러오지 못했습니다.</strong>
          <small>{errorMessage}</small>
          <button type='button' onClick={() => setReloadKey((key) => key + 1)}>다시 시도</button>
        </Message>
      )}

      {status === 'ready' && points.length === 0 && (
        <Message>
          <strong>표시할 데이터가 없습니다.</strong>
          <small>{range === '1D' ? '장 시작 전이거나 오늘 체결 내역이 없습니다.' : '해당 기간의 일봉이 없습니다.'}</small>
        </Message>
      )}

      {status === 'ready' && points.length > 0 && (
        <>
          <div className='chart-summary'>
            <strong>{won(lastPrice)}</strong>
            <span className={rising ? 'positive' : 'negative'}>
              {rising ? '+' : ''}{changeRate.toFixed(2)}%
            </span>
          </div>

          <div className='chart-stats' aria-label='차트 주요 값'>
            <div><span>{range === '1D' ? '전일 종가' : '기간 시작'}</span><strong>{won(firstPrice)}</strong></div>
            <div><span>기간 고가</span><strong>{won(highPrice)}</strong></div>
            <div><span>기간 저가</span><strong>{won(lowPrice)}</strong></div>
            <div><span>기간 등락</span><strong className={rising ? 'positive' : 'negative'}>{rising ? '+' : ''}{changeRate.toFixed(2)}%</strong></div>
          </div>

          <div className='chart-area'>
            {showCandle ? (
              <CandleChart bars={dailyBars} />
            ) : (
              <ResponsiveContainer width='100%' height='100%'>
                <LineChart data={points} margin={{ top: 8, right: 6, bottom: 0, left: 0 }}>
                  <CartesianGrid stroke='#e1e1e1' strokeDasharray='3 3' />
                  <XAxis dataKey='label' minTickGap={36} tick={{ fill: '#666', fontSize: 11 }} />
                  <YAxis
                    orientation='right'
                    domain={[(min) => Math.floor(min * 0.998), (max) => Math.ceil(max * 1.002)]}
                    tickFormatter={(value) => Math.round(value).toLocaleString('ko-KR')}
                    tick={{ fill: '#666', fontSize: 11 }}
                    width={70}
                  />
                  <Tooltip content={<ChartTooltip firstPrice={firstPrice} />} />
                  <ReferenceLine y={firstPrice} stroke='#888' strokeDasharray='4 4' />
                  <Line
                    type='monotone'
                    dataKey='price'
                    stroke={rising ? '#1769aa' : '#c43b3b'}
                    strokeWidth={2}
                    dot={false}
                    activeDot={{ r: 4 }}
                    isAnimationActive={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            )}
          </div>
        </>
      )}
    </div>
  )
}