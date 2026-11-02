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

const minuteOptions = ['1초', '5초', '30초', '1분', '3분', '5분', '10분', '15분', '30분', '60분']

function formatKRW(value) {
  if (value == null) return '—'
  return `₩${Number(value).toLocaleString('ko-KR')}`
}

function CandlestickLayer({ candles }) {
  const xScale = useXAxisScale()
  const yScale = useYAxisScale()
  const plotArea = usePlotArea()
  if (!xScale || !yScale || !plotArea || !candles.length) return null

  const candleWidth = Math.max(1, Math.min(20, plotArea.width / candles.length * 0.65))

  return (
    <g className='recharts-candlesticks' pointerEvents='none'>
      {candles.map(({ date, open, close, high, low }) => {
        if (![open, close, high, low].every(Number.isFinite)) return null
        const x = xScale(date, { position: 'middle' })
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

export default function StockChart({ symbol }) {
  const [chartType, setChartType] = useState('candle')
  const [selectedPeriod, setSelectedPeriod] = useState('일')
  const [selectedMinute, setSelectedMinute] = useState('1초')
  const [isMinuteOpen, setIsMinuteOpen] = useState(false)
  const dropdownRef = useRef(null)

  const periodCode = useMemo(() => {
    switch (selectedPeriod) {
      case '주': return 'W'
      case '월': return 'M'
      case '년': return 'Y'
      default: return 'D'
    }
  }, [selectedPeriod])

  const { data: candles, loading, error } = useStockCandles(symbol, periodCode, 45)

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target)) {
        setIsMinuteOpen(false)
      }
    }
    document.addEventListener('pointerdown', handleClickOutside)
    return () => document.removeEventListener('pointerdown', handleClickOutside)
  }, [])

  const { minPrice, maxPrice, firstClose, lastClose, highPrice, lowPrice, changeRate, isRising } =
    useMemo(() => {
      if (!candles || candles.length === 0) {
        return {
          minPrice: 0,
          maxPrice: 100,
          firstClose: 0,
          lastClose: 0,
          highPrice: 0,
          lowPrice: 0,
          changeRate: 0,
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
        minPrice: Math.floor(minL * 0.98),
        maxPrice: Math.ceil(maxH * 1.02),
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
          {selectedPeriod === '분' ? `${selectedMinute} 시세 (실시간 준비 중)` : `${selectedPeriod}봉 시세 · KIS 실전 데이터`}
        </span>
        <span>
          {loading ? '시세 데이터 수신 중...' : error ? error : `${candles.length}개 봉 수신 완료`}
        </span>
      </div>

      <div className='chart-summary'>
        <strong>{formatKRW(lastClose)}</strong>
        <span className={isRising ? 'positive' : 'negative'}>
          {isRising ? '+' : ''}{changeRate.toFixed(2)}%
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
          <strong className={isRising ? 'positive' : 'negative'}>
            {isRising ? '+' : ''}{changeRate.toFixed(2)}%
          </strong>
        </div>
      </div>

      <div className='chart-area'>
        {loading && candles.length === 0 ? (
          <div className='skeleton-block' style={{ height: '100%' }} />
        ) : (
          <ResponsiveContainer width='100%' height='100%'>
            {chartType === 'candle' ? (
              <BarChart
                data={candles}
                margin={{ top: 12, right: 6, bottom: 0, left: 0 }}
              >
                <CartesianGrid stroke='#e1e1e1' strokeDasharray='3 3' />
                <XAxis
                  dataKey='date'
                  minTickGap={28}
                  tickFormatter={(val) => (val && val.length > 5 ? val.slice(5) : val)}
                  tick={{ fill: '#666', fontSize: 11 }}
                />
                <YAxis
                  orientation='right'
                  domain={[minPrice, maxPrice]}
                  tickFormatter={(val) => Number(val).toLocaleString('ko-KR')}
                  tick={{ fill: '#666', fontSize: 11 }}
                  width={68}
                />
                <Tooltip content={<ChartTooltip />} />
                <ReferenceLine y={firstClose} stroke='#888' strokeDasharray='4 4' />
                <Bar dataKey='high' fill='transparent' isAnimationActive={false} />
                <CandlestickLayer candles={candles} />
              </BarChart>
            ) : (
              <LineChart
                data={candles}
                margin={{ top: 12, right: 6, bottom: 0, left: 0 }}
              >
                <CartesianGrid stroke='#e1e1e1' strokeDasharray='3 3' />
                <XAxis
                  dataKey='date'
                  minTickGap={28}
                  tickFormatter={(val) => (val && val.length > 5 ? val.slice(5) : val)}
                  tick={{ fill: '#666', fontSize: 11 }}
                />
                <YAxis
                  orientation='right'
                  domain={[minPrice, maxPrice]}
                  tickFormatter={(val) => Number(val).toLocaleString('ko-KR')}
                  tick={{ fill: '#666', fontSize: 11 }}
                  width={68}
                />
                <Tooltip content={<ChartTooltip />} />
                <ReferenceLine y={firstClose} stroke='#888' strokeDasharray='4 4' />
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
