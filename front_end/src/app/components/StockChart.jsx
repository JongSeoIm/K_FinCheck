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
  { value: '1D', label: '1일', description: '당일' },
  { value: '1W', label: '1주', description: '최근 1주 · 일봉' },
  { value: '1M', label: '1개월', description: '최근 1개월 · 일봉' },
  { value: '1Y', label: '1년', description: '최근 1년 · 일봉' },
]

// 1일 차트의 간격 (봉 하나가 몇 분짜리인지). 1초는 화면을 연 뒤 받은 실시간 체결로 만듭니다.
const intervals = [
  { value: '1s', label: '1초', minutes: 0, description: '1초 간격 · 화면을 연 뒤 받은 실시간 체결' },
  { value: '1m', label: '1분', minutes: 1, description: '1분 간격 · 1분봉 + 실시간 체결 누적' },
  { value: '5m', label: '5분', minutes: 5, description: '5분 간격 · 1분봉을 5개씩 묶음' },
  { value: '10m', label: '10분', minutes: 10, description: '10분 간격 · 1분봉을 10개씩 묶음' },
  { value: '30m', label: '30분', minutes: 30, description: '30분 간격 · 1분봉을 30개씩 묶음' },
  { value: '60m', label: '60분', minutes: 60, description: '60분 간격 · 1분봉을 60개씩 묶음' },
]
const MAX_SECONDS = 3600 // 1초 간격은 최근 1시간까지만 보관

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')

// 브라우저(한국) 기준 현재 시각 "HH:MM", 오늘 날짜 "YYYY-MM-DD"
const nowMinute = () => {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
const todayString = () => new Date().toLocaleDateString('sv-SE')
const nowSecond = () => new Date().toTimeString().slice(0, 8)

// "HH:MM" → 분, 분 → "HH:MM"
const toMinutes = (time) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5))
const toTime = (minutes) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`

// 캔들 차트용 시각 (한국 시각을 그대로 보이게 UTC 값으로 넣습니다)
const toTimestamp = (date, time) => {
  const [y, m, d] = date.split('-').map(Number)
  return Date.UTC(y, m - 1, d, Number(time.slice(0, 2)), Number(time.slice(3, 5)), Number(time.slice(6, 8) || 0)) / 1000
}

// 1분봉을 N분 단위로 묶기 (09:00 기준). 시가=첫 봉 시가, 종가=마지막 봉 종가, 고가·저가=최고·최저
function groupBars(minuteBars, size) {
  if (size <= 1) return minuteBars
  const groups = new Map()
  const open = 9 * 60
  minuteBars.forEach((bar) => {
    const start = open + Math.floor((toMinutes(bar.time) - open) / size) * size
    const key = toTime(start)
    const group = groups.get(key)
    if (!group) {
      groups.set(key, { ...bar, time: key })
    } else {
      group.high = Math.max(group.high, bar.high)
      group.low = Math.min(group.low, bar.low)
      group.close = bar.close
    }
  })
  return [...groups.values()]
}

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

export default function StockChart({ code }) {
  const [range, setRange] = useState('1D')
  const [bars, setBars] = useState([])
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [errorMessage, setErrorMessage] = useState('')
  const [livePoints, setLivePoints] = useState({})
  const [liveSeconds, setLiveSeconds] = useState({})
  const [interval, setBarInterval] = useState('1m')
  const [reloadKey, setReloadKey] = useState(0)
  const [mode, setMode] = useState('line') // line | candle (캔들은 일봉 기간에서만)

  // 1) Spring(KIS)에서 분봉/일봉 불러오기 (1일은 30초마다 다시)
  useEffect(() => {
    const controller = new AbortController()
    let timer

    const load = async (first) => {
      try {
        const response = await fetch(`/api/kis/candles/${code}?range=${range}`, {
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

    load(true)
    if (range === '1D') timer = setInterval(() => load(false), 30000)

    return () => {
      controller.abort()
      clearInterval(timer)
    }
  }, [code, range, reloadKey])

  // 기간을 바꾸거나 다시 시도하면 이전 데이터를 비우고 새로 불러옵니다
  const changeRange = (value) => {
    if (value === range) return
    setBars([])
    setLivePoints({})
    setLiveSeconds({})
    setStatus('loading')
    setRange(value)
  }
  const retry = () => {
    setStatus('loading')
    setReloadKey((key) => key + 1)
  }

  // 서버 연결이 끊기면 차트 대신 빈 상태 안내를 보여 주고, 다시 연결되면 차트를 새로 불러옵니다
  const offline = useStockStore((state) => state.connection.state === 'disconnected')
  useEffect(() => useStockStore.subscribe((state, previous) => {
    if (previous.connection.state === 'disconnected' && state.connection.state !== 'disconnected') {
      setStatus('loading')
      setReloadKey((key) => key + 1)
    }
  }), [])

  // 2) 실시간 시세(WebSocket push)를 1일 차트에 분 단위·초 단위로 누적합니다. 종목 정보와 같은 값을 씁니다.
  const live = useStockStore((state) => state.quotes[code])

  useEffect(() => {
    if (range !== '1D') return undefined
    const accumulate = (quote) => {
      if (!quote?.price) return
      // 체결 시각이 있으면 그 시각, 없으면 받은 시각 기준
      const second = quote.tradeTime && quote.tradeTime.length === 8 ? quote.tradeTime : nowSecond()
      const key = quote.tradeTime ? quote.tradeTime.slice(0, 5) : nowMinute()
      if (key < '09:00' || key > '15:30') return // 장 시간 밖은 누적하지 않음
      const price = Number(quote.price)
      setLivePoints((current) => (current[key] === price ? current : { ...current, [key]: price }))
      setLiveSeconds((current) => {
        if (current[second] === price) return current
        const next = { ...current, [second]: price }
        const keys = Object.keys(next)
        if (keys.length > MAX_SECONDS) keys.sort().slice(0, keys.length - MAX_SECONDS).forEach((old) => delete next[old])
        return next
      })
    }
    return useStockStore.subscribe((state, previous) => {
      if (state.quotes[code] !== previous.quotes[code]) accumulate(state.quotes[code])
    })
  }, [code, range])

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

  // 1일: 1분봉 + 실시간 누적 → 고른 간격으로 묶은 봉
  const intradayBars = useMemo(() => {
    if (range !== '1D') return []
    const map = new Map(bars.map((bar) => [bar.time, { ...bar }]))
    const today = todayString()
    // 오늘 분봉이 있을 때만 실시간 값을 이어 붙입니다 (휴장일 방지).
    if (bars.length > 0 && bars[0].date === today) {
      Object.entries(livePoints).forEach(([key, price]) => {
        const bar = map.get(key)
        if (bar) {
          bar.close = price
          bar.high = Math.max(bar.high, price)
          bar.low = Math.min(bar.low, price)
        } else {
          map.set(key, { date: today, time: key, open: price, high: price, low: price, close: price })
        }
      })
    }
    const minuteBars = [...map.values()].sort((a, b) => a.time.localeCompare(b.time))
    const size = intervals.find((item) => item.value === interval)?.minutes || 1
    return groupBars(minuteBars, size).map((bar) => ({ ...bar, ts: toTimestamp(bar.date, bar.time) }))
  }, [bars, livePoints, range, interval])

  const points = useMemo(() => {
    if (range === '1D' && interval === '1s') {
      const today = todayString()
      return Object.entries(liveSeconds)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([second, price]) => ({ label: second, full: `${today} ${second}`, price }))
    }
    if (range === '1D') {
      return intradayBars.map((bar) => ({ label: bar.time, full: `${bar.date} ${bar.time}`, price: bar.close, high: bar.high, low: bar.low }))
    }
    return dailyBars.map((bar) => ({
      label: bar.date.slice(5).replace('-', '/'),
      full: bar.date,
      price: bar.close,
      high: bar.high,
      low: bar.low,
    }))
  }, [range, interval, liveSeconds, intradayBars, dailyBars])

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
  const selectedInterval = intervals.find((item) => item.value === interval)
  const liveCount = Object.keys(livePoints).length
  const secondCount = Object.keys(liveSeconds).length
  const isSecond = range === '1D' && interval === '1s'
  const showCandle = mode === 'candle' && !isSecond

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
            disabled={isSecond}
            title={isSecond ? '1초 간격은 선 차트로만 볼 수 있습니다' : undefined}
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
                onClick={() => changeRange(item.value)}
              >
                {item.label}
              </button>
            </Fragment>
          ))}
        </div>
      </div>

      {range === '1D' && (
        <div className='chart-controls'>
          <div className='range-tabs' aria-label='차트 간격 선택'>
            <span style={{ color: '#666', fontSize: 13, marginRight: 4 }}>간격</span>
            {intervals.map((item, index) => (
              <Fragment key={item.value}>
                {index > 0 && <span className='tab-separator' aria-hidden='true'>·</span>}
                <button
                  type='button'
                  className={interval === item.value ? 'active' : ''}
                  aria-pressed={interval === item.value}
                  onClick={() => setBarInterval(item.value)}
                >
                  {item.label}
                </button>
              </Fragment>
            ))}
          </div>
        </div>
      )}

      <div className='stream-status'>
        <span>{range === '1D' ? `${selectedRange.description} · ${selectedInterval.description}` : selectedRange.description}</span>
        <span>
          {status === 'error'
            ? '연결 오류'
            : isSecond
              ? secondCount > 0 ? `실시간 체결 누적 중 · ${secondCount}초` : '체결 대기 (화면을 연 뒤부터 쌓입니다)'
              : range === '1D'
                ? liveCount > 0 ? `실시간 체결 누적 중 · ${liveCount}분` : '장 마감 또는 체결 대기'
                : showCandle ? '일봉 캔들 (시가·고가·저가·종가)' : '일봉 종가'}
          {status === 'ready' && errorMessage ? ' · 갱신 지연' : ''}
        </span>
      </div>

      {offline && (
        <Message>
          <strong>서버에 연결할 수 없어 차트를 표시할 수 없습니다.</strong>
          <small>연결이 돌아오면 자동으로 다시 불러옵니다.</small>
        </Message>
      )}

      {!offline && status === 'loading' && <Message>차트를 불러오는 중…</Message>}

      {!offline && status === 'error' && (
        <Message>
          <strong>차트를 불러오지 못했습니다.</strong>
          <small>{errorMessage}</small>
          <button type='button' onClick={retry}>다시 시도</button>
        </Message>
      )}

      {!offline && status === 'ready' && points.length === 0 && (
        <Message>
          <strong>표시할 데이터가 없습니다.</strong>
          <small>
            {isSecond
              ? '1초 간격은 화면을 연 뒤 받은 실시간 체결로 그립니다. 장중에 잠시 기다려 주세요.'
              : range === '1D' ? '장 시작 전이거나 오늘 체결 내역이 없습니다.' : '해당 기간의 일봉이 없습니다.'}
          </small>
        </Message>
      )}

      {!offline && status === 'ready' && points.length > 0 && (
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
              <CandleChart
                key={`${range}-${interval}`}
                bars={range === '1D' ? intradayBars : dailyBars}
                intraday={range === '1D'}
              />
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
                    stroke={rising ? '#d6453d' : '#2f6fdb'}
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