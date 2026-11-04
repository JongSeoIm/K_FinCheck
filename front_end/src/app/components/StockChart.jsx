'use client'

import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import useStockStore from '@/app/store/useStockStore'

// 초·분봉: 서버가 체결을 집계한 봉 / 일·주·월·년봉: KIS 기간별 조회
const liveIntervals = [
  { value: '1', label: '1초' },
  { value: '5', label: '5초' },
  { value: '30', label: '30초' },
  { value: '60', label: '1분' },
  { value: '180', label: '3분' },
  { value: '300', label: '5분' },
  { value: '600', label: '10분' },
  { value: '900', label: '15분' },
  { value: '1800', label: '30분' },
  { value: '3600', label: '60분' },
]
const periods = [
  { value: 'D', label: '일' },
  { value: 'W', label: '주' },
  { value: 'M', label: '월' },
  { value: 'Y', label: '년' },
]

const POLL_MS = 2000
const MIN_VISIBLE = 10
const ZOOM_STEP = 1.2

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')
const isLiveSelection = (selection) => /^\d+$/.test(selection)
const todayString = () => new Date().toLocaleDateString('sv-SE')

// 표시 범위에 맞는 시간축 글자
function axisLabel(bar, selection) {
  if (bar.time) return Number(selection) < 60 ? bar.time : bar.time.slice(0, 5)
  if (selection === 'Y') return bar.date.slice(0, 4)
  if (selection === 'M') return bar.date.slice(0, 7).replace('-', '/')
  return bar.date.slice(5).replace('-', '/')
}

// 고가~저가 막대 위치를 받아 꼬리와 몸통을 그립니다. 상승 빨강 · 하락 파랑.
function Candle({ x, y, width, height, payload }) {
  const { open, close, high, low } = payload
  const color = close >= open ? '#d6453d' : '#2f6fdb'
  const ratio = high === low ? 0 : height / (high - low)
  const top = y + (high - Math.max(open, close)) * ratio
  const bottom = y + (high - Math.min(open, close)) * ratio
  const center = x + width / 2

  return (
    <g>
      <line x1={center} x2={center} y1={y} y2={y + height} stroke={color} />
      <rect x={x} y={top} width={width} height={Math.max(1, bottom - top)} fill={color} />
    </g>
  )
}

function ChartTooltip({ active, payload }) {
  if (!active || !payload?.length) return null
  const bar = payload[0].payload

  return (
    <div className='chart-tooltip'>
      <span>{bar.full}</span>
      <span>시 {won(bar.open)} · 고 {won(bar.high)}</span>
      <span>저 {won(bar.low)} · 종 {won(bar.close)}</span>
      {bar.volume > 0 && <span>거래량 {Number(bar.volume).toLocaleString('ko-KR')}주</span>}
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

// 선택한 간격의 봉을 받아 그립니다. 간격이 바뀌면 부모가 key 로 새로 만듭니다.
function ChartBody({ code, selection, mode }) {
  const live = isLiveSelection(selection)
  const feedMode = useStockStore((state) => state.feedMode)
  const quote = useStockStore((state) => state.quotes[code])
  const offline = useStockStore((state) => state.connection.state === 'disconnected')

  const [bars, setBars] = useState([])
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [errorMessage, setErrorMessage] = useState('')
  const [visibleCount, setVisibleCount] = useState(null) // null = 전체
  const [reloadKey, setReloadKey] = useState(0)
  const chartRef = useRef(null)

  // 서버에서 봉 받기. Polling 방식의 초·분봉은 응답 후 2초마다 다시 받습니다.
  useEffect(() => {
    const controller = new AbortController()
    const repeat = live && feedMode === 'poll'
    const url = live ? `/api/kis/bars/${code}?interval=${selection}` : `/api/kis/period-candles/${code}?period=${selection}`
    let timer

    const load = async (first) => {
      try {
        const response = await fetch(url, { signal: controller.signal, cache: 'no-store' })
        const json = await response.json()
        if (!response.ok || !Array.isArray(json)) throw new Error(json.error || '차트 조회에 실패했습니다.')
        setBars(json)
        setStatus('ready')
        setErrorMessage('')
      } catch (requestError) {
        if (requestError.name === 'AbortError') return
        setErrorMessage(requestError.message)
        if (first) setStatus('error') // 갱신 중 실패는 기존 차트를 그대로 둡니다.
      }
      if (repeat && !controller.signal.aborted) timer = setTimeout(() => load(false), POLL_MS)
    }

    load(true)

    return () => {
      controller.abort()
      clearTimeout(timer)
    }
  }, [code, selection, live, feedMode, reloadKey])

  // Push 방식의 초·분봉: 서버가 보낸 최신 봉을 합칩니다.
  useEffect(() => {
    if (!live || feedMode === 'poll') return undefined
    return useStockStore.subscribe((state, previous) => {
      const update = state.barUpdate
      if (update === previous.barUpdate || update?.code !== code) return
      const bar = update.bars.find((item) => String(item.interval) === selection)
      if (!bar) return
      setBars((current) => {
        const last = current.at(-1)
        if (last?.time === bar.time) return [...current.slice(0, -1), bar]
        return !last || bar.time > last.time ? [...current, bar] : current
      })
    })
  }, [code, selection, live, feedMode])

  // 서버 연결이 돌아오면 다시 불러옵니다.
  useEffect(() => useStockStore.subscribe((state, previous) => {
    if (previous.connection.state === 'disconnected' && state.connection.state !== 'disconnected') {
      setStatus('loading')
      setReloadKey((key) => key + 1)
    }
  }), [])

  // 일봉의 오늘 봉은 현재가로 맞춥니다.
  const points = useMemo(() => {
    const list = bars.map((bar) => ({
      ...bar,
      label: axisLabel(bar, selection),
      full: bar.date || `${todayString()} ${bar.time}`,
    }))
    const last = list.at(-1)
    if (selection === 'D' && quote?.price && last?.date === todayString()) {
      const price = Number(quote.price)
      last.close = price
      last.high = Math.max(last.high, price)
      last.low = Math.min(last.low, price)
    }
    return list
  }, [bars, selection, quote])

  // 마우스 휠: 위로 확대, 아래로 축소 (최신 봉 기준)
  const pointCount = points.length
  const hasChart = status === 'ready' && pointCount > 0 && !offline
  useEffect(() => {
    const element = chartRef.current
    if (!element) return undefined
    const handleWheel = (event) => {
      event.preventDefault()
      setVisibleCount((current) => {
        const count = current ?? pointCount
        const next = event.deltaY < 0 ? Math.floor(count / ZOOM_STEP) : Math.ceil(count * ZOOM_STEP)
        return Math.min(pointCount, Math.max(Math.min(MIN_VISIBLE, pointCount), next))
      })
    }
    element.addEventListener('wheel', handleWheel, { passive: false })
    return () => element.removeEventListener('wheel', handleWheel)
  }, [hasChart, pointCount])

  const visible = visibleCount == null ? points : points.slice(-visibleCount)

  // 표시 범위의 고가·저가에 맞춰 가격축을 조정합니다.
  const highPrice = visible.length ? Math.max(...visible.map((bar) => bar.high)) : 0
  const lowPrice = visible.length ? Math.min(...visible.map((bar) => bar.low)) : 0
  const padding = Math.max((highPrice - lowPrice) * 0.08, highPrice * 0.0005)
  const firstPrice = visible[0]?.open ?? 0
  const lastPrice = visible.at(-1)?.close ?? 0
  const rising = lastPrice >= firstPrice
  const changeRate = firstPrice ? ((lastPrice - firstPrice) / firstPrice) * 100 : 0

  if (offline) {
    return (
      <Message>
        <strong>서버에 연결할 수 없어 차트를 표시할 수 없습니다.</strong>
        <small>연결이 돌아오면 자동으로 다시 불러옵니다.</small>
      </Message>
    )
  }
  if (status === 'loading') return <Message>차트를 불러오는 중…</Message>
  if (status === 'error') {
    return (
      <Message>
        <strong>차트를 불러오지 못했습니다.</strong>
        <small>{errorMessage}</small>
        <button
          type='button'
          onClick={() => {
            setStatus('loading')
            setReloadKey((key) => key + 1)
          }}
        >
          다시 시도
        </button>
      </Message>
    )
  }
  if (points.length === 0) {
    return (
      <Message>
        <strong>표시할 데이터가 없습니다.</strong>
        <small>{live ? '초·분봉은 서버가 켜진 뒤 수신한 체결로 만듭니다. 장중에 잠시 기다려 주세요.' : '해당 기간의 시세가 없습니다.'}</small>
      </Message>
    )
  }

  return (
    <>
      <div className='stream-status'>
        <span>{live ? `연결 후 수신분 · 봉 ${points.length}개` : `KIS 기간별 조회 · 봉 ${points.length}개`}</span>
        <span>표시 {visible.length}개 · 마우스 휠로 확대·축소{errorMessage ? ' · 갱신 지연' : ''}</span>
      </div>

      <div className='chart-summary'>
        <strong>{won(lastPrice)}</strong>
        <span className={rising ? 'positive' : 'negative'}>{rising ? '+' : ''}{changeRate.toFixed(2)}%</span>
      </div>

      <div className='chart-stats' aria-label='차트 주요 값'>
        <div><span>표시 시작</span><strong>{won(firstPrice)}</strong></div>
        <div><span>표시 고가</span><strong>{won(highPrice)}</strong></div>
        <div><span>표시 저가</span><strong>{won(lowPrice)}</strong></div>
        <div><span>표시 등락</span><strong className={rising ? 'positive' : 'negative'}>{rising ? '+' : ''}{changeRate.toFixed(2)}%</strong></div>
      </div>

      <div className='chart-area' ref={chartRef}>
        <ResponsiveContainer width='100%' height='100%'>
          <ComposedChart data={visible} margin={{ top: 8, right: 6, bottom: 0, left: 0 }}>
            <CartesianGrid stroke='#e1e1e1' strokeDasharray='3 3' />
            <XAxis dataKey='label' minTickGap={36} tick={{ fill: '#666', fontSize: 11 }} />
            <YAxis
              orientation='right'
              domain={[lowPrice - padding, highPrice + padding]}
              tickFormatter={(value) => Math.round(value).toLocaleString('ko-KR')}
              tick={{ fill: '#666', fontSize: 11 }}
              width={70}
            />
            <Tooltip content={<ChartTooltip />} />
            <ReferenceLine y={firstPrice} stroke='#888' strokeDasharray='4 4' />
            {mode === 'candle' ? (
              <Bar dataKey={(bar) => [bar.low, bar.high]} shape={<Candle />} isAnimationActive={false} />
            ) : (
              <Line
                type='monotone'
                dataKey='close'
                stroke={rising ? '#d6453d' : '#2f6fdb'}
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4 }}
                isAnimationActive={false}
              />
            )}
          </ComposedChart>
        </ResponsiveContainer>
      </div>
    </>
  )
}

function SelectionTabs({ label, items, selection, onSelect }) {
  return (
    <div className='range-tabs' aria-label={label}>
      {items.map((item, index) => (
        <Fragment key={item.value}>
          {index > 0 && <span className='tab-separator' aria-hidden='true'>·</span>}
          <button
            type='button'
            className={selection === item.value ? 'active' : ''}
            aria-pressed={selection === item.value}
            onClick={() => onSelect(item.value)}
          >
            {item.label}
          </button>
        </Fragment>
      ))}
    </div>
  )
}

export default function StockChart({ code }) {
  const [selection, setSelection] = useState('D')
  const [mode, setMode] = useState('line') // line | candle

  return (
    <div className='chart-container'>
      <div className='chart-controls'>
        <div className='stream-mode' aria-label='차트 종류 선택'>
          <button type='button' className={mode === 'line' ? 'active' : ''} onClick={() => setMode('line')}>선</button>
          <span className='tab-separator' aria-hidden='true'>·</span>
          <button type='button' className={mode === 'candle' ? 'active' : ''} onClick={() => setMode('candle')}>캔들</button>
        </div>
        <SelectionTabs label='일 주 월 년 선택' items={periods} selection={selection} onSelect={setSelection} />
      </div>

      <div className='chart-controls'>
        <SelectionTabs label='초 분 간격 선택' items={liveIntervals} selection={selection} onSelect={setSelection} />
      </div>

      <ChartBody key={selection} code={code} selection={selection} mode={mode} />
    </div>
  )
}