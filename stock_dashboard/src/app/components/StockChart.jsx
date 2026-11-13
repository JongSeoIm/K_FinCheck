'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  useXAxisScale,
  useYAxisScale,
  usePlotArea,
} from 'recharts'
import useStockCandles from '../hooks/useStockCandles'
import useIntradayCandles from '../hooks/useIntradayCandles'
import { mergeAndAggregate } from '../lib/intraday.mjs'

const timeAxisFormatter = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
const datedTimeFormatter = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const timeTickSteps = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400]

const minuteOptions = ['1분', '3분', '5분', '10분', '15분', '30분', '60분']

function formatKRW(value) {
  if (value == null) return '—'
  return `₩${Number(value).toLocaleString('ko-KR')}`
}

function CandlestickLayer({ candles }) {
  const xScale = useXAxisScale()
  const yScale = useYAxisScale()
  const plotArea = usePlotArea()
  if (!xScale || !yScale || !plotArea || !candles.length) return null

  const positions = candles.map(candle => xScale(candle.timestamp ?? candle.date, { position: 'middle' }))
  const distances = positions.slice(1).map((x, index) => x - positions[index]).filter(distance => Number.isFinite(distance) && distance > 0)
  const spacing = distances.length ? Math.min(...distances) : plotArea.width
  const candleWidth = Math.max(1, Math.min(20, spacing * 0.65))

  return (
    <g className='recharts-candlesticks' pointerEvents='none'>
      {candles.map(({ date, timestamp, open, close, high, low }) => {
        if (![open, close, high, low].every(Number.isFinite)) return null
        const x = xScale(timestamp ?? date, { position: 'middle' })
        const yHigh = yScale(high)
        const yLow = yScale(low)
        const yOpen = yScale(open)
        const yClose = yScale(close)
        if (![x, yHigh, yLow, yOpen, yClose].every(Number.isFinite)) return null
        const color = close >= open ? '#c43b3b' : '#1769aa'
        return (
          <g key={date}>
            <line x1={x} y1={yHigh} x2={x} y2={yLow} stroke={color} strokeWidth={1.5} />
            <rect x={x - candleWidth / 2} y={Math.min(yOpen, yClose)}
              width={candleWidth} height={Math.max(Math.abs(yClose - yOpen), 1)}
              fill={color} stroke={color} />
          </g>
        )
      })}
    </g>
  )
}

function ChartTooltip({ active, payload }) {
  if (!active || !payload?.length) return null
  const item = payload[0].payload
  if (!item) return null

  const isUp = item.close >= item.open
  const change = item.close - item.open
  const changeRate = item.open ? (change / item.open) * 100 : 0

  return (
    <div className='chart-tooltip'>
      <span>{item.date}</span>
      <div>
        <strong>종가: {formatKRW(item.close)}</strong>
        <span className={isUp ? 'positive' : 'negative'}>
          {isUp ? '+' : ''}{changeRate.toFixed(2)}%
        </span>
      </div>
      <small>시가: {formatKRW(item.open)}</small>
      <small>고가: {formatKRW(item.high)}</small>
      <small>저가: {formatKRW(item.low)}</small>
      <small>거래량: {Number(item.volume).toLocaleString('ko-KR')}주</small>
    </div>
  )
}

export default function StockChart({ symbol, liveQuote, liveCandles, streamInterval, streamStatus, supported, onIntervalChange }) {
  const [chartType, setChartType] = useState('candle')
  const [selectedPeriod, setSelectedPeriod] = useState('일')
  const [selectedMinute, setSelectedMinute] = useState('1분')
  const [isMinuteOpen, setIsMinuteOpen] = useState(false)
  const dropdownRef = useRef(null)
  const chartAreaRef = useRef(null)
  const lastWheelRef = useRef(0)
  const [zoom, setZoom] = useState(null)
  const [chartWidth, setChartWidth] = useState(800)

  const periodCode = useMemo(() => {
    switch (selectedPeriod) {
      case '주': return 'W'
      case '월': return 'M'
      case '년': return 'Y'
      default: return 'D'
    }
  }, [selectedPeriod])

  const isIntraday = selectedPeriod === '분'
  const seconds = selectedMinute.endsWith('초') ? parseInt(selectedMinute, 10) : parseInt(selectedMinute, 10) * 60
  const { data: historicalCandles, loading: historicalLoading, error: historicalError } = useStockCandles(symbol, periodCode, 45, !isIntraday)
  const intraday = useIntradayCandles(symbol, isIntraday)
  const { loadMore, hasMore, loading: intradayLoading } = intraday
  const loading = isIntraday ? intraday.loading && !intraday.data.length : historicalLoading
  const error = isIntraday ? intraday.error : historicalError
  const candles = useMemo(() => {
    if (isIntraday) return mergeAndAggregate(intraday.data, streamInterval === 60 ? (liveCandles || []) : [], seconds)
    if (periodCode !== 'D' || !liveQuote || !historicalCandles.length) return historicalCandles
    const day = { date: liveQuote.businessDate, open: liveQuote.open, high: liveQuote.high,
      low: liveQuote.low, close: liveQuote.price, volume: liveQuote.volume }
    const last = historicalCandles.at(-1)
    if (last.date === day.date) return [...historicalCandles.slice(0, -1), day]
    if (last.date < day.date) return [...historicalCandles, day]
    return historicalCandles
  }, [isIntraday, streamInterval, seconds, liveCandles, periodCode, liveQuote, historicalCandles, intraday.data])

  const viewKey = `${symbol}:${selectedPeriod}:${isIntraday ? seconds : periodCode}`
  const visibleCount = zoom?.key === viewKey ? zoom.count : isIntraday ? 120 : null
  const visibleCandles = useMemo(() => (
    visibleCount == null ? candles : candles.slice(-visibleCount)
  ), [candles, visibleCount])
  const chartCandles = useMemo(() => (
    isIntraday ? visibleCandles.map((candle, index) => ({ ...candle, timestamp: index, actualTimestamp: Date.parse(candle.date) })) : visibleCandles
  ), [visibleCandles, isIntraday])
  const timeAxis = useMemo(() => {
    if (!isIntraday || !chartCandles.length) return null
    const targetTicks = Math.max(2, Math.min(8, Math.floor((chartWidth - 74) / 100)))
    const desiredStep = Math.max(seconds, chartCandles.length * seconds / (targetTicks - 1))
    const step = timeTickSteps.find(value => value >= desiredStep) || 86400
    const ticks = []
    for (let i = 0; i < chartCandles.length; i++) {
      if (chartCandles[i].actualTimestamp % (step * 1000) === 0 && (!ticks.length || i - ticks.at(-1) >= chartCandles.length / targetTicks / 2)) ticks.push(i)
    }
    if (!ticks.length) ticks.push(0)
    return { dataKey: 'timestamp', type: 'number',
      domain: [-0.5, chartCandles.length - 0.5], ticks, interval: 'preserveStartEnd', minTickGap: 16, allowDataOverflow: true }
  }, [isIntraday, chartCandles, chartWidth, seconds])
  const formatTimeTick = value => {
    if (!isIntraday) return value && value.length > 5 ? value.slice(5) : value
    const candle = chartCandles[Math.round(value)]
    if (!candle) return ''
    const multipleDays = chartCandles.length > 1 && new Date(chartCandles[0].actualTimestamp + 9 * 3600000).toISOString().slice(0, 10) !== new Date(chartCandles.at(-1).actualTimestamp + 9 * 3600000).toISOString().slice(0, 10)
    return (multipleDays ? datedTimeFormatter : timeAxisFormatter).format(new Date(candle.actualTimestamp))
  }

  const [minPrice, maxPrice] = useMemo(() => {
    if (!visibleCandles.length) return [0, 100]
    const low = Math.min(...visibleCandles.map(candle => candle.low))
    const high = Math.max(...visibleCandles.map(candle => candle.high))
    // Pad the observed price range, rather than adding 2% of the stock price.
    const padding = Math.max((high - low) * 0.12, high * 0.00005, 1)
    return [Math.max(0, Math.floor(low - padding)), Math.ceil(high + padding)]
  }, [visibleCandles])

  useEffect(() => {
    const area = chartAreaRef.current
    if (!area) return
    const handleWheel = event => {
      if (event.ctrlKey || event.deltaY === 0 || !candles.length) return
      event.preventDefault()
      const now = Date.now()
      if (now - lastWheelRef.current < 60) return
      lastWheelRef.current = now
      const count = zoom?.key === viewKey ? Math.min(zoom.count ?? candles.length, candles.length) : Math.min(isIntraday ? 120 : candles.length, candles.length)
      if (event.deltaY > 0 && count >= candles.length && isIntraday && hasMore && !intradayLoading) loadMore()
      setZoom(current => {
        const count = current?.key === viewKey
          ? Math.min(current.count ?? candles.length, candles.length)
          : Math.min(isIntraday ? 120 : candles.length, candles.length)
        const next = event.deltaY < 0
          ? Math.max(8, Math.floor(count * 0.8))
          : Math.min(candles.length, Math.ceil(count * 1.25))
        return { key: viewKey, count: next >= candles.length ? null : next }
      })
    }
    area.addEventListener('wheel', handleWheel, { passive: false })
    return () => area.removeEventListener('wheel', handleWheel)
  }, [viewKey, candles.length, isIntraday, zoom, hasMore, intradayLoading, loadMore])

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setIsMinuteOpen(false)
      }
    }
    document.addEventListener('pointerdown', handleClickOutside)
    return () => document.removeEventListener('pointerdown', handleClickOutside)
  }, [])

  const { firstClose, lastClose, highPrice, lowPrice, changeRate, isRising } =
    useMemo(() => {
      if (!candles || candles.length === 0) {
        return {
          firstClose: null,
          lastClose: null,
          highPrice: null,
          lowPrice: null,
          changeRate: null,
          isRising: true,
        }
      }

      const lows = candles.map((c) => c.low)
      const highs = candles.map((c) => c.high)
      const minL = Math.min(...lows)
      const maxH = Math.max(...highs)

      const first = candles[0].close
      const last = candles.at(-1).close
      const rate = first ? ((last - first) / first) * 100 : 0

      return {
        firstClose: first,
        lastClose: last,
        highPrice: maxH,
        lowPrice: minL,
        changeRate: rate,
        isRising: last >= first,
      }
    }, [candles])

  const handleSelectMinute = (m) => {
    setSelectedMinute(m)
    onIntervalChange?.(60)
    setSelectedPeriod('분')
    setIsMinuteOpen(false)
  }

  const handleSelectPeriod = (p) => {
    setSelectedPeriod(p)
    setIsMinuteOpen(false)
  }

  return (
    <div className='chart-container'>
      <div className='chart-controls'>
        <div className='stream-mode' aria-label='차트 종류 선택'>
          <button
            type='button'
            className={chartType === 'candle' ? 'active' : ''}
            onClick={() => setChartType('candle')}
          >
            캔들 차트
          </button>
          <span className='tab-separator' aria-hidden='true'>·</span>
          <button
            type='button'
            className={chartType === 'line' ? 'active' : ''}
            onClick={() => setChartType('line')}
          >
            선 차트
          </button>
        </div>

        {/* 두 번째 사진과 동일한 글씨 클릭형 밑줄 탭 UI: 1분 ⌄ · 일 · 주 · 월 · 년 */}
        <div className='range-tabs' aria-label='차트 주기 선택'>
          <div className='minute-dropdown-container' ref={dropdownRef}>
            <button
              type='button'
              className={`text-tab-btn minute-btn ${selectedPeriod === '분' ? 'active' : ''}`}
              onClick={() => setIsMinuteOpen(!isMinuteOpen)}
              aria-expanded={isMinuteOpen}
            >
              <span>{selectedMinute}</span>
              <svg width='8' height='5' viewBox='0 0 10 6' fill='none' aria-hidden='true'>
                <path d='M1 1L5 5L9 1' stroke='currentColor' strokeWidth='1.6' strokeLinecap='round' />
              </svg>
            </button>

            {isMinuteOpen && (
              <ul className='minute-popover-menu'>
                {minuteOptions.map((m) => (
                  <li key={m}>
                    <button
                      type='button'
                      className={`minute-item-btn ${selectedMinute === m ? 'selected' : ''}`}
                      onClick={() => handleSelectMinute(m)}
                    >
                      {m}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <span className='tab-separator' aria-hidden='true'>·</span>
          <button
            type='button'
            className={selectedPeriod === '일' ? 'active' : ''}
            onClick={() => handleSelectPeriod('일')}
          >
            일
          </button>
          <span className='tab-separator' aria-hidden='true'>·</span>
          <button
            type='button'
            className={selectedPeriod === '주' ? 'active' : ''}
            onClick={() => handleSelectPeriod('주')}
          >
            주
          </button>
          <span className='tab-separator' aria-hidden='true'>·</span>
          <button
            type='button'
            className={selectedPeriod === '월' ? 'active' : ''}
            onClick={() => handleSelectPeriod('월')}
          >
            월
          </button>
          <span className='tab-separator' aria-hidden='true'>·</span>
          <button
            type='button'
            className={selectedPeriod === '년' ? 'active' : ''}
            onClick={() => handleSelectPeriod('년')}
          >
            년
          </button>
        </div>
      </div>

      <div className='stream-status'>
        <span>
          {isIntraday ? `${selectedMinute}봉 · KIS 과거 분봉 + 실시간 수신분` : `${selectedPeriod}봉 시세 · KIS`}
        </span>
        <span>
          {loading ? '시세 데이터 수신 중...' : error || `${visibleCandles.length < candles.length ? `${visibleCandles.length}/${candles.length}` : candles.length}개 봉 · ${supported ? streamStatus : '실시간 연결 종목 확인'}`}
        </span>
      </div>

      {isIntraday && <div className='stream-status'>
        <span>{intraday.loading ? '과거 분봉 조회 중…' : intraday.hasMore ? '더 축소하면 이전 분봉을 불러옵니다.' : '조회 가능한 분봉 범위입니다.'}</span>
        {intraday.error && <button type='button' onClick={intraday.retry}>다시 조회</button>}
      </div>}

      <div className='chart-summary'>
        <strong>{formatKRW(lastClose)}</strong>
        <span className={changeRate == null ? '' : isRising ? 'positive' : 'negative'}>
          {changeRate == null ? '—' : `${isRising ? '+' : ''}${changeRate.toFixed(2)}%`}
        </span>
      </div>

      <div className='chart-stats' aria-label='차트 주요 값'>
        <div>
          <span>기간 시작 종가</span>
          <strong>{formatKRW(firstClose)}</strong>
        </div>
        <div>
          <span>기간 최고가</span>
          <strong>{formatKRW(highPrice)}</strong>
        </div>
        <div>
          <span>기간 최저가</span>
          <strong>{formatKRW(lowPrice)}</strong>
        </div>
        <div>
          <span>기간 등락률</span>
          <strong className={changeRate == null ? '' : isRising ? 'positive' : 'negative'}>
            {changeRate == null ? '—' : `${isRising ? '+' : ''}${changeRate.toFixed(2)}%`}
          </strong>
        </div>
      </div>

      <div className='chart-area' ref={chartAreaRef} aria-label='주가 차트, 마우스 휠로 확대 및 축소'>
        {loading && candles.length === 0 ? (
          <div className='skeleton-block' style={{ height: '100%' }} />
        ) : candles.length === 0 ? (
          <div className='empty-state' role='status'>{isIntraday ? (supported ? streamStatus : '실시간 구독 종목은 삼성전자·SK하이닉스입니다.') : (error || '차트 데이터가 없습니다.')}</div>
        ) : (
          <ResponsiveContainer width='100%' height='100%' onResize={width => setChartWidth(width)}>
            {chartType === 'candle' ? (
              <BarChart
                data={chartCandles}
                margin={{ top: 12, right: 6, bottom: 0, left: 0 }}
              >
                <CartesianGrid stroke='#e1e1e1' strokeDasharray='3 3' />
                <XAxis
                  {...(timeAxis || { dataKey: 'date', minTickGap: 28 })}
                  tickFormatter={formatTimeTick}
                  tick={{ fill: '#666', fontSize: 12 }}
                />
                <YAxis
                  orientation='right'
                  domain={[minPrice, maxPrice]}
                  allowDataOverflow
                  tickFormatter={(val) => Number(val).toLocaleString('ko-KR')}
                  tick={{ fill: '#666', fontSize: 12 }}
                  width={76}
                />
                <Tooltip content={<ChartTooltip />} />
                <ReferenceLine y={visibleCandles[0]?.close} stroke='#888' strokeDasharray='4 4' />
                <Bar dataKey='high' fill='transparent' isAnimationActive={false} />
                <CandlestickLayer candles={chartCandles} />
              </BarChart>
            ) : (
              <LineChart
                data={chartCandles}
                margin={{ top: 12, right: 6, bottom: 0, left: 0 }}
              >
                <CartesianGrid stroke='#e1e1e1' strokeDasharray='3 3' />
                <XAxis
                  {...(timeAxis || { dataKey: 'date', minTickGap: 28 })}
                  tickFormatter={formatTimeTick}
                  tick={{ fill: '#666', fontSize: 12 }}
                />
                <YAxis
                  orientation='right'
                  domain={[minPrice, maxPrice]}
                  allowDataOverflow
                  tickFormatter={(val) => Number(val).toLocaleString('ko-KR')}
                  tick={{ fill: '#666', fontSize: 12 }}
                  width={76}
                />
                <Tooltip content={<ChartTooltip />} />
                <ReferenceLine y={visibleCandles[0]?.close} stroke='#888' strokeDasharray='4 4' />
                <Line
                  type='monotone'
                  dataKey='close'
                  stroke={isRising ? '#c43b3b' : '#1769aa'}
                  strokeWidth={2}
                  dot={false}
                  activeDot={{ r: 4 }}
                  isAnimationActive={false}
                />
              </LineChart>
            )}
          </ResponsiveContainer>
        )}
      </div>
    </div>
  )
}
