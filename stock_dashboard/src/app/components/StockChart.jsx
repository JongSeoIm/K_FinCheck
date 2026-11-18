'use client'

import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  Bar,
  CartesianGrid,
  ComposedChart,
  Line,
  Rectangle,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  useXAxisScale,
  useYAxisInverseScale,
  useYAxisScale,
  usePlotArea,
} from 'recharts'
import useStockCandles from '../hooks/useStockCandles'
import useIntradayCandles from '../hooks/useIntradayCandles'
import { changeRateClass } from '../lib/market'
import { mergeAndAggregate } from '../lib/intraday.mjs'

const timeAxisFormatter = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
const datedTimeFormatter = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const timeTickSteps = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400]

const minuteOptions = ['1분', '3분', '5분', '10분', '15분', '30분', '60분']
const movingAverages = [
  { key: 'ma5', period: 5, color: '#966026' },
  { key: 'ma20', period: 20, color: '#7251a3' },
]
const axisTick = { fill: '#666', fontSize: 12 }
const axisWidth = 76
const chartMargin = { top: 8, right: 6, bottom: 0, left: 0 }
const volumeFormatter = new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 })

function withMovingAverages(candles) {
  const sums = movingAverages.map(() => 0)
  const counts = movingAverages.map(() => 0)
  return candles.map((candle, index) => {
    const item = { ...candle, previousClose: candles[index - 1]?.close }
    movingAverages.forEach(({ key, period }, series) => {
      const previous = candles[index - period]?.close
      if (Number.isFinite(candle.close)) { sums[series] += candle.close; counts[series]++ }
      if (Number.isFinite(previous)) { sums[series] -= previous; counts[series]-- }
      item[key] = counts[series] === period ? sums[series] / period : null
    })
    return item
  })
}

function formatKRW(value) {
  if (value == null) return '—'
  return `${Number(value).toLocaleString('ko-KR')}원`
}

function priceTicks(low, high) {
  const interval = (high - low) / 4
  const magnitude = 10 ** Math.floor(Math.log10(interval))
  const step = Math.max(1, magnitude * [1, 2, 2.5, 5, 10].find(value => value * magnitude >= interval))
  const first = Math.max(0, Math.floor(low / step) * step)
  const last = Math.ceil(high / step) * step
  return Array.from({ length: Math.round((last - first) / step) + 1 }, (_, index) => first + index * step)
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

function ChartTooltip({ active, payload, showAverages }) {
  if (!active || !payload?.length) return null
  const item = payload[0].payload
  if (!item) return null

  const changeRate = item.previousClose > 0 ? (item.close / item.previousClose - 1) * 100 : null

  return (
    <div className='chart-tooltip'>
      <time dateTime={item.date}>{Number.isFinite(item.actualTimestamp) ? datedTimeFormatter.format(item.actualTimestamp) : item.date}</time>
      <dl>
        <dt>종가</dt><dd><strong>{formatKRW(item.close)}</strong></dd>
        {changeRate != null && <><dt>전봉 대비</dt><dd className={changeRateClass(changeRate)}>
          {changeRate > 0 ? '+' : ''}{changeRate.toFixed(2)}%
        </dd></>}
        <dt>시가</dt><dd>{formatKRW(item.open)}</dd>
        <dt>고가</dt><dd>{formatKRW(item.high)}</dd>
        <dt>저가</dt><dd>{formatKRW(item.low)}</dd>
        <dt>거래량</dt><dd>{Number.isFinite(item.volume) ? `${item.volume.toLocaleString('ko-KR')}주` : '—'}</dd>
        {showAverages && movingAverages.filter(({ key }) => item[key] != null).map(({ key, period, color }) =>
          <div key={key} className='chart-tooltip-average' style={{ color }}><dt>{period}봉 평균</dt><dd>{formatKRW(Math.round(item[key]))}</dd></div>)}
      </dl>
    </div>
  )
}

function PriceGuide({ pointer, latestPrice }) {
  const plot = usePlotArea()
  const scale = useYAxisScale()
  const invert = useYAxisInverseScale()
  if (!plot || !scale || !invert) return null
  const right = plot.x + plot.width
  const bottom = plot.y + plot.height
  const hovering = pointer && pointer.x >= plot.x && pointer.x <= right && pointer.y >= plot.y && pointer.y <= bottom
  const y = hovering ? pointer.y : scale(latestPrice)
  const price = hovering ? invert(y) : latestPrice
  if (!Number.isFinite(y) || !Number.isFinite(price) || y < plot.y || y > bottom) return null
  const x = right + 2
  const labelY = Math.max(plot.y + 9, Math.min(bottom - 9, y))
  return <g className={hovering ? 'chart-cursor-price' : 'chart-latest-price'} pointerEvents='none'>
    <line x1={plot.x} x2={right} y1={y} y2={y} stroke='#627084' strokeDasharray='3 3' />
    <rect x={x} y={labelY - 9} width={axisWidth - 4} height={18} rx={2} fill='#354457' />
    <text x={x + (axisWidth - 4) / 2} y={labelY} dominantBaseline='central' textAnchor='middle'
      fill='#fff' fontSize={11}>{Math.round(price).toLocaleString('ko-KR')}</text>
  </g>
}

function VolumeBar({ payload, x, y, width, height }) {
  return <Rectangle x={x} y={y} width={width} height={height} fill={payload.close >= payload.open ? '#c43b3b' : '#1769aa'} fillOpacity={0.55} />
}

export default function StockChart({ symbol, liveQuote, liveCandles, streamInterval, onIntervalChange }) {
  const [chartType, setChartType] = useState('candle')
  const [showAverages, setShowAverages] = useState(true)
  const chartSyncId = useId()
  const [selectedPeriod, setSelectedPeriod] = useState('일')
  const [selectedMinute, setSelectedMinute] = useState('1분')
  const [isMinuteOpen, setIsMinuteOpen] = useState(false)
  const dropdownRef = useRef(null)
  const chartAreaRef = useRef(null)
  const priceAreaRef = useRef(null)
  const lastWheelRef = useRef(0)
  const dragRef = useRef(null)
  const [zoom, setZoom] = useState(null)
  const [pointer, setPointer] = useState(null)
  const [panning, setPanning] = useState(false)
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
  const { data: historicalCandles, loading: historicalLoading, error: historicalError } = useStockCandles(symbol, periodCode, 100, !isIntraday)
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
  const currentView = zoom?.key === viewKey ? zoom : null
  const count = Math.min(currentView ? currentView.count ?? candles.length : isIntraday ? 120 : 45, candles.length)
  const anchorIndex = currentView?.endDate ? candles.findIndex(candle => candle.date === currentView.endDate) : -1
  const end = anchorIndex < 0 ? candles.length : Math.max(count, anchorIndex + 1)
  const start = end - count
  const canLoadMore = isIntraday && hasMore && !intradayLoading
  const candlesWithAverages = useMemo(() => withMovingAverages(candles), [candles])
  const visibleCandles = useMemo(() => candlesWithAverages.slice(start, end), [candlesWithAverages, start, end])
  const chartCandles = useMemo(() => (
    isIntraday ? visibleCandles.map((candle, index) => ({ ...candle, timestamp: index, actualTimestamp: Date.parse(candle.date) })) : visibleCandles
  ), [visibleCandles, isIntraday])
  const timeAxis = useMemo(() => {
    if (!isIntraday || !chartCandles.length) return null
    const targetTicks = Math.max(2, Math.min(8, Math.floor((chartWidth - axisWidth) / 100)))
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

  const visiblePriceTicks = useMemo(() => {
    const values = visibleCandles.flatMap(candle => [candle.low, candle.high,
      ...(showAverages ? movingAverages.map(({ key }) => candle[key]) : [])]).filter(Number.isFinite)
    if (!values.length) return [0, 100]
    const low = Math.min(...values)
    const high = Math.max(...values)
    const padding = Math.max((high - low) * 0.12, high * 0.00005, 1)
    return priceTicks(Math.max(0, low - padding), high + padding)
  }, [visibleCandles, showAverages])

  const adjustZoom = useCallback(zoomIn => {
    if (!candles.length) return
    if (!zoomIn && start === 0 && canLoadMore) loadMore()
    const next = zoomIn ? Math.min(count, Math.max(8, Math.floor(count * 0.8))) : Math.min(candles.length, Math.ceil(count * 1.25))
    const nextEnd = Math.max(end, next)
    setZoom({ key: viewKey, count: next === candles.length ? null : next, endDate: nextEnd < candles.length ? candles[nextEnd - 1].date : null })
  }, [viewKey, candles, count, start, end, canLoadMore, loadMore])

  function beginPan(event) {
    if (event.button !== 0 || !count || event.target.closest('button')) return
    dragRef.current = { id: event.pointerId, x: event.clientX, y: event.clientY, endDate: candles[end - 1].date, moved: false, atStart: false }
  }

  function movePointer(event) {
    const drag = dragRef.current
    if (drag?.id === event.pointerId) {
      const distance = event.clientX - drag.x
      if (!drag.moved) {
        if (Math.abs(distance) < 5 || Math.abs(distance) < Math.abs(event.clientY - drag.y)) return
        drag.moved = true
        event.currentTarget.setPointerCapture(event.pointerId)
        setPointer(null)
        setPanning(true)
      }
      const initialEnd = candles.findIndex(candle => candle.date === drag.endDate) + 1 || end
      const candlesMoved = Math.round(distance * count / Math.max(1, chartWidth - axisWidth))
      const nextEnd = Math.min(candles.length, Math.max(count, initialEnd - candlesMoved))
      drag.atStart = nextEnd === count && distance > 0
      if (nextEnd !== end) setZoom({ key: viewKey, count, endDate: nextEnd < candles.length ? candles[nextEnd - 1].date : null })
      return
    }
    const area = priceAreaRef.current?.getBoundingClientRect()
    setPointer(area && event.pointerType !== 'touch'
      ? { x: event.clientX - area.left, y: event.clientY - area.top } : null)
  }

  function endPan(event) {
    const drag = dragRef.current
    if (drag?.id !== event.pointerId) return
    dragRef.current = null
    setPanning(false)
    if (drag.atStart && canLoadMore) loadMore()
  }

  useEffect(() => {
    const area = chartAreaRef.current
    if (!area) return
    const handleWheel = event => {
      if (event.ctrlKey || event.deltaY === 0 || !candles.length || dragRef.current?.moved) return
      event.preventDefault()
      const now = Date.now()
      if (now - lastWheelRef.current < 60) return
      lastWheelRef.current = now
      adjustZoom(event.deltaY < 0)
    }
    area.addEventListener('wheel', handleWheel, { passive: false })
    return () => area.removeEventListener('wheel', handleWheel)
  }, [candles.length, adjustZoom])

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setIsMinuteOpen(false)
      }
    }
    document.addEventListener('pointerdown', handleClickOutside)
    return () => document.removeEventListener('pointerdown', handleClickOutside)
  }, [])

  const { firstClose, highPrice, lowPrice, changeRate, isRising } =
    useMemo(() => {
      if (visibleCandles.length === 0) {
        return {
          firstClose: null,
          highPrice: null,
          lowPrice: null,
          changeRate: null,
          isRising: true,
        }
      }

      const lows = visibleCandles.map((c) => c.low)
      const highs = visibleCandles.map((c) => c.high)
      const minL = Math.min(...lows)
      const maxH = Math.max(...highs)

      const first = visibleCandles[0].close
      const last = visibleCandles.at(-1).close
      const rate = first ? ((last - first) / first) * 100 : 0

      return {
        firstClose: first,
        highPrice: maxH,
        lowPrice: minL,
        changeRate: rate,
        isRising: last >= first,
      }
    }, [visibleCandles])

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

  const horizontalAxis = timeAxis || { dataKey: 'date', scale: 'band', minTickGap: 28 }
  const hasVolume = chartCandles.some(candle => Number.isFinite(candle.volume))
  const latestPrice = end === candles.length ? candles.at(-1)?.close : undefined

  return (
    <div className='chart-container'>
      <div className='chart-controls'>
        <div className='chart-type' role='group' aria-label='차트 종류 선택'>
          <button
            type='button'
            className={chartType === 'candle' ? 'active' : ''}
            aria-pressed={chartType === 'candle'}
            onClick={() => setChartType('candle')}
          >
            캔들
          </button>
          <span className='tab-separator' aria-hidden='true'>·</span>
          <button
            type='button'
            className={chartType === 'line' ? 'active' : ''}
            aria-pressed={chartType === 'line'}
            onClick={() => setChartType('line')}
          >
            선
          </button>
        </div>

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

      {error && <div className='chart-status' role='status'>
        <span>{error}</span>
        {isIntraday && <button type='button' onClick={intraday.retry}>다시 조회</button>}
      </div>}

      {candles.length > 0 && <div className='chart-stats' aria-label='차트 주요 값'>
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
          <strong className={changeRateClass(changeRate)}>
            {changeRate == null ? '—' : `${isRising ? '+' : ''}${changeRate.toFixed(2)}%`}
          </strong>
        </div>
      </div>}

      <div ref={chartAreaRef} className={panning ? 'chart-pan-area is-panning' : 'chart-pan-area'}
        aria-label='주가 차트, 드래그로 이동하고 마우스 휠로 확대 및 축소'
        onPointerDown={beginPan} onPointerMove={movePointer} onPointerUp={endPan}
        onPointerCancel={endPan} onLostPointerCapture={endPan}
        onPointerLeave={() => { if (!dragRef.current?.moved) dragRef.current = null; setPointer(null) }}>
        {chartCandles.length > 0 && <div className='chart-indicators'>
          <button type='button' aria-pressed={showAverages} onClick={() => setShowAverages(visible => !visible)}>
            이동평균선
            {movingAverages.map(({ key, period, color }) => <span key={key} style={{ color }}>{period}</span>)}
          </button>
        </div>}
        <div className='chart-area' ref={priceAreaRef}>
          {loading && chartCandles.length === 0 ? (
            <div className='skeleton-block chart-loading' role='status'><span className='sr-only'>차트 불러오는 중</span></div>
          ) : chartCandles.length === 0 ? (
            !error && <div className='empty-state' role='status'>차트 데이터가 없습니다.</div>
          ) : (
            <ResponsiveContainer width='100%' height='100%' onResize={width => setChartWidth(width)}>
              <ComposedChart data={chartCandles} margin={chartMargin} syncId={chartSyncId}>
                <CartesianGrid vertical={false} stroke='#e6e6e6' />
                <XAxis {...horizontalAxis} hide={hasVolume} tickFormatter={formatTimeTick} tick={axisTick} />
                <YAxis orientation='right' domain={[visiblePriceTicks[0], visiblePriceTicks.at(-1)]} ticks={visiblePriceTicks} allowDataOverflow
                  tickFormatter={value => value.toLocaleString('ko-KR')} tick={axisTick} width={axisWidth} />
                <Tooltip cursor={!panning} content={<ChartTooltip showAverages={showAverages} />} />
                {chartType === 'candle' ? (
                  <>
                    <Bar dataKey='high' fill='transparent' isAnimationActive={false} />
                    <CandlestickLayer candles={chartCandles} />
                  </>
                ) : (
                  <Line type='monotone' dataKey='close' stroke={isRising ? '#c43b3b' : '#1769aa'}
                    strokeWidth={2} dot={false} activeDot={{ r: 4 }} isAnimationActive={false} />
                )}
                {showAverages && movingAverages.map(({ key, color }) => <Line key={key} className={`chart-${key}`}
                  dataKey={key} stroke={color} strokeWidth={1.5} dot={false} activeDot={false} isAnimationActive={false} />)}
                <PriceGuide pointer={pointer} latestPrice={latestPrice} />
              </ComposedChart>
            </ResponsiveContainer>
          )}
        </div>
        {hasVolume && <div className='chart-volume'>
          <span className='chart-volume-label'>거래량</span>
          <ResponsiveContainer width='100%' height='100%'>
            <ComposedChart data={chartCandles} margin={chartMargin} syncId={chartSyncId}>
              <CartesianGrid vertical={false} stroke='#eeeeee' />
              <XAxis {...horizontalAxis} tickFormatter={formatTimeTick} tick={axisTick} />
              <YAxis orientation='right' domain={[0, 'auto']} tickCount={3} width={axisWidth}
                tickFormatter={value => volumeFormatter.format(value)} tick={axisTick} />
              <Tooltip cursor={!panning} content={() => null} />
              <Bar dataKey='volume' shape={<VolumeBar />} maxBarSize={20} isAnimationActive={false} />
            </ComposedChart>
          </ResponsiveContainer>
        </div>}
      </div>
    </div>
  )
}
