'use client'

import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
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

// 초·분봉(서버 집계)과 일·주·월·년봉(KIS 기간별 시세)
const secondIntervals = [
  { value: '1s', label: '1초' },
  { value: '5s', label: '5초' },
  { value: '30s', label: '30초' },
]
const minuteIntervals = [
  { value: '1m', label: '1분' },
  { value: '3m', label: '3분' },
  { value: '5m', label: '5분' },
  { value: '10m', label: '10분' },
  { value: '15m', label: '15분' },
  { value: '30m', label: '30분' },
  { value: '60m', label: '60분' },
]
const periodIntervals = [
  { value: 'D', label: '일' },
  { value: 'W', label: '주' },
  { value: 'M', label: '월' },
  { value: 'Y', label: '년' },
]

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')
const REFRESH_MS = { s: 3000, m: 15000, period: 15000 }
const MIN_VISIBLE = 10

const isIntraday = (interval) => interval.endsWith('s') || interval.endsWith('m')

function pointLabel(bar, interval) {
  if (isIntraday(interval)) return bar.time
  if (interval === 'Y') return bar.date.slice(0, 4)
  if (interval === 'M') return bar.date.slice(2, 7).replace('-', '/')
  return bar.date.slice(5).replace('-', '/')
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
      style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '8px', color: '#666', textAlign: 'center', padding: '0 16px' }}
    >
      {children}
    </div>
  )
}

export default function StockChart({ symbol }) {
  const [interval, setIntervalValue] = useState('1m')
  const [bars, setBars] = useState([])
  const [meta, setMeta] = useState({ source: '', note: '' })
  const [status, setStatus] = useState('loading') // loading | ready | error
  const [errorMessage, setErrorMessage] = useState('')
  const [reloadKey, setReloadKey] = useState(0)
  const [mode, setMode] = useState('line') // line | candle
  const [view, setView] = useState(null) // null=전체, 아니면 { span, fromEnd } (선 차트 확대 상태)
  const [fitSignal, setFitSignal] = useState(0)
  const areaRef = useRef(null)
  const pointsLengthRef = useRef(0)

  // 1) 서버에서 봉 데이터 받기 (초봉은 3초, 그 외는 15초마다 다시)
  useEffect(() => {
    const controller = new AbortController()
    let timer

    const load = async (first) => {
      try {
        if (first) setStatus('loading')
        const response = await fetch(`/api/chart/${symbol}?interval=${interval}`, {
          signal: controller.signal,
          cache: 'no-store',
        })
        const json = await response.json()
        if (!response.ok || !Array.isArray(json.bars)) {
          throw new Error(json.error || '차트 조회에 실패했습니다.')
        }
        setBars(json.bars)
        setMeta({ source: json.source, note: json.note })
        setStatus('ready')
        setErrorMessage('')
      } catch (requestError) {
        if (requestError.name === 'AbortError') return
        setErrorMessage(requestError.message)
        if (first) setStatus('error') // 갱신 중 실패는 기존 차트를 그대로 둡니다.
      }
    }

    setBars([])
    setView(null)
    load(true)
    const ms = interval.endsWith('s') ? REFRESH_MS.s : interval.endsWith('m') ? REFRESH_MS.m : REFRESH_MS.period
    timer = setInterval(() => load(false), ms)

    return () => {
      controller.abort()
      clearInterval(timer)
    }
  }, [symbol, interval, reloadKey])

  // 현재가 영역과 같은 전일 종가 (서버가 보낸 시세)
  const live = useStockStore((state) => state.prices[symbol])

  const points = useMemo(() => bars.map((bar) => ({
    label: pointLabel(bar, interval),
    full: bar.time ? `${bar.date} ${bar.time}` : bar.date,
    price: bar.close,
    high: bar.high,
    low: bar.low,
  })), [bars, interval])
  pointsLengthRef.current = points.length

  // 선 차트의 확대 구간: 오른쪽 끝(최신)을 기준으로 span 개를 보여 줍니다.
  const visible = useMemo(() => {
    if (!view || view.span >= points.length) return points
    const end = Math.max(view.span, points.length - view.fromEnd)
    return points.slice(end - view.span, end)
  }, [points, view])

  // 마우스 휠로 확대·축소 (페이지가 같이 스크롤되지 않도록 직접 등록)
  const showLine = mode === 'line'
  useEffect(() => {
    const el = areaRef.current
    if (!el || !showLine) return undefined
    const onWheel = (event) => {
      const total = pointsLengthRef.current
      if (total <= MIN_VISIBLE) return
      event.preventDefault()
      const rect = el.getBoundingClientRect()
      const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width))
      setView((prev) => {
        const span = prev ? Math.min(prev.span, total) : total
        const fromEnd = prev ? prev.fromEnd : 0
        const factor = event.deltaY < 0 ? 0.8 : 1.25
        const nextSpan = Math.min(total, Math.max(MIN_VISIBLE, Math.round(span * factor)))
        if (nextSpan >= total) return null
        // 마우스 위치의 봉이 화면에서 같은 자리에 머물도록 이동량을 맞춥니다.
        const startIndex = total - fromEnd - span
        const anchor = startIndex + ratio * span
        const nextStart = Math.round(anchor - ratio * nextSpan)
        const clamped = Math.min(total - nextSpan, Math.max(0, nextStart))
        return { span: nextSpan, fromEnd: total - clamped - nextSpan }
      })
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [showLine, status, points.length > 0])

  // 끌어서 좌우로 이동 (선 차트)
  const dragRef = useRef(null)
  const onMouseDown = useCallback((event) => {
    if (!view) return
    dragRef.current = { x: event.clientX, fromEnd: view.fromEnd, span: view.span, width: areaRef.current?.clientWidth || 1 }
    const onMove = (moveEvent) => {
      const drag = dragRef.current
      if (!drag) return
      const total = pointsLengthRef.current
      const shift = Math.round(((moveEvent.clientX - drag.x) / drag.width) * drag.span)
      const next = Math.min(total - drag.span, Math.max(0, drag.fromEnd + shift))
      setView({ span: drag.span, fromEnd: next })
    }
    const onUp = () => {
      dragRef.current = null
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }, [view])

  const prices = points.map((point) => point.price)
  // 초·분봉은 전일 종가 기준(현재가 영역과 같은 등락률), 일 이상은 기간 시작 종가 기준
  const intraday = isIntraday(interval)
  const firstPrice = intraday && live?.prevClose ? Number(live.prevClose) : (prices[0] ?? 0)
  const lastPrice = prices.at(-1) ?? 0
  // 기간 고가/저가는 종가가 아니라 봉의 고가·저가 기준
  const highs = points.map((point) => point.high || point.price)
  const lows = points.map((point) => point.low || point.price)
  const highPrice = highs.length ? Math.max(...highs) : 0
  const lowPrice = lows.length ? Math.min(...lows) : 0
  const rising = lastPrice >= firstPrice
  const changeRate = firstPrice ? ((lastPrice - firstPrice) / firstPrice) * 100 : 0
  const zoomed = Boolean(view)
  const showSeconds = interval.endsWith('s')

  const renderTabs = (items, withLabel) => (
    <div className='range-tabs' aria-label={withLabel}>
      {items.map((item, index) => (
        <Fragment key={item.value}>
          {index > 0 && <span className='tab-separator' aria-hidden='true'>·</span>}
          <button
            type='button'
            className={interval === item.value ? 'active' : ''}
            aria-pressed={interval === item.value}
            onClick={() => setIntervalValue(item.value)}
          >
            {item.label}
          </button>
        </Fragment>
      ))}
    </div>
  )

  return (
    <div className='chart-container'>
      <div className='chart-controls' style={{ flexWrap: 'wrap', gap: 8 }}>
        <div className='stream-mode' aria-label='차트 종류 선택'>
          <button type='button' className={mode === 'line' ? 'active' : ''} onClick={() => setMode('line')}>선</button>
          <span className='tab-separator' aria-hidden='true'>·</span>
          <button type='button' className={mode === 'candle' ? 'active' : ''} onClick={() => setMode('candle')}>캔들</button>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'flex-end' }}>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            {renderTabs(secondIntervals, '초봉 선택')}
            {renderTabs(minuteIntervals, '분봉 선택')}
          </div>
          {renderTabs(periodIntervals, '일·주·월·년봉 선택')}
        </div>
      </div>

      <div className='stream-status'>
        <span>{meta.source ? `${meta.source} · ${meta.note}` : ''}</span>
        <span>
          {status === 'error' ? '연결 오류' : ''}
          {status === 'ready' && errorMessage ? ' 갱신 지연' : ''}
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
          <small>
            {showSeconds
              ? '아직 수집된 체결 변동이 없습니다. 초봉은 장중에 서버가 수집한 값만 쌓이고, 서버를 다시 시작하면 초기화됩니다.'
              : intraday
                ? '장 시작 전이거나 오늘 체결 내역이 없습니다.'
                : '해당 주기의 봉이 없습니다.'}
          </small>
        </Message>
      )}

      {status === 'ready' && points.length > 0 && (
        <>
          <div className='chart-summary'>
            <strong>{won(lastPrice)}</strong>
            <span className={rising ? 'positive' : 'negative'}>
              {rising ? '+' : ''}{changeRate.toFixed(2)}%
            </span>
            <span style={{ marginLeft: 'auto', display: 'flex', gap: 10, alignItems: 'center', fontSize: '0.75rem', color: '#888' }}>
              마우스 휠로 확대·축소{showLine ? ', 끌어서 이동' : ''}
              <button
                type='button'
                onClick={() => { setView(null); setFitSignal((n) => n + 1) }}
                disabled={showLine && !zoomed}
                style={{ border: '1px solid #888', background: '#fff', padding: '1px 8px', fontSize: '0.75rem', cursor: 'pointer' }}
              >
                전체 보기
              </button>
            </span>
          </div>

          <div className='chart-stats' aria-label='차트 주요 값'>
            <div><span>{intraday ? '전일 종가' : '기간 시작'}</span><strong>{won(firstPrice)}</strong></div>
            <div><span>기간 고가</span><strong>{won(highPrice)}</strong></div>
            <div><span>기간 저가</span><strong>{won(lowPrice)}</strong></div>
            <div><span>기간 등락</span><strong className={rising ? 'positive' : 'negative'}>{rising ? '+' : ''}{changeRate.toFixed(2)}%</strong></div>
          </div>

          <div className='chart-area' ref={areaRef} onMouseDown={showLine ? onMouseDown : undefined} style={{ cursor: showLine && zoomed ? 'grab' : undefined }}>
            {!showLine ? (
              <CandleChart
                key={interval}
                bars={bars}
                intraday={intraday}
                showSeconds={showSeconds}
                fitSignal={fitSignal}
              />
            ) : (
              <ResponsiveContainer width='100%' height='100%'>
                <LineChart data={visible} margin={{ top: 8, right: 6, bottom: 0, left: 0 }}>
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
                    type='linear'
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
