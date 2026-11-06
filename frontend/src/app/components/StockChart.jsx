'use client'

import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { stockApi } from '@/api/stockApi'
import useChartZoom from '@/app/hooks/useChartZoom'
import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'
import { mergeCandle, mergeDailyQuote, periodStats } from '@/app/lib/chartData'
import { directionClass, formatPrice, formatRate } from '@/app/lib/format'
import CandleChart from './CandleChart'
import PriceLineChart from './PriceLineChart'

const INTRADAY_INTERVALS = [
  { value: '1s', label: '1초' },
  { value: '5s', label: '5초' },
  { value: '30s', label: '30초' },
  { value: '1m', label: '1분' },
  { value: '3m', label: '3분' },
  { value: '5m', label: '5분' },
  { value: '10m', label: '10분' },
  { value: '15m', label: '15분' },
  { value: '30m', label: '30분' },
  { value: '60m', label: '60분' },
]
const PERIOD_INTERVALS = [
  { value: '1d', label: '일' },
  { value: '1w', label: '주' },
  { value: '1mo', label: '월' },
  { value: '1y', label: '년' },
]
const INTERVALS = [...INTRADAY_INTERVALS, ...PERIOD_INTERVALS]
const POLL_REFRESH_MS = 2000 // Polling: 응답 후 2초
const PUSH_REFRESH_MS = 60000 // Push: 봉은 서버가 보내고, 놓친 값은 1분마다 다시 맞춘다
const DEFAULT_CANDLES = 120 // 캔들이 너무 가늘어지지 않게 처음에 보여줄 봉 수

const isSeconds = (interval) => interval.endsWith('s')
const isIntraday = (interval) => isSeconds(interval) || interval.endsWith('m')

// 15:20~15:30 은 장 마감 동시호가라 연속 체결이 없다 (한국 시간 기준)
function inClosingAuction() {
  const [h, m] = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul' })
    .format(new Date()).split(':').map(Number)
  const minutes = h * 60 + m
  return minutes >= 15 * 60 + 20 && minutes < 15 * 60 + 30
}

function sourceNote(interval) {
  if (isSeconds(interval)) return '연결 후 수신한 체결 집계'
  if (interval.endsWith('m')) return '당일 KIS 분봉 + 수신 체결'
  return 'KIS 기간별 조회'
}

// `1분 ⌄` 초·분봉 선택. 마지막으로 고른 주기를 기억해 일·주·월·년에서 돌아올 때 보여준다
function IntradayPicker({ interval, onSelect }) {
  const [open, setOpen] = useState(false)
  const [lastValue, setLastValue] = useState('1m')
  const rootRef = useRef(null)
  const active = isIntraday(interval)
  const shown = active ? interval : lastValue

  useEffect(() => {
    if (!open) return undefined
    const close = (event) => {
      if (event.key === 'Escape' || (event.type === 'mousedown' && !rootRef.current?.contains(event.target))) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', close)
    }
  }, [open])

  return (
    <div className='interval-picker' ref={rootRef}>
      <button
        type='button'
        className={active ? 'active' : ''}
        aria-haspopup='listbox'
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        {INTRADAY_INTERVALS.find((item) => item.value === shown).label}
        <span aria-hidden='true'>⌄</span>
      </button>
      {open && (
        <ul className='interval-menu' role='listbox' aria-label='초·분봉 주기'>
          {INTRADAY_INTERVALS.map((item) => (
            <li key={item.value}>
              <button
                type='button'
                role='option'
                aria-selected={item.value === shown}
                className={item.value === shown ? 'selected' : ''}
                onClick={() => {
                  setLastValue(item.value)
                  setOpen(false)
                  onSelect(item.value)
                }}
              >
                {item.label}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default function StockChart({ symbol, marketOpen }) {
  const hydrated = useHydrated()
  const [interval, setInterval] = useState('1m')
  const [chartType, setChartType] = useState('line')
  const [refreshKey, setRefreshKey] = useState(0)
  // 응답에 요청 키를 함께 저장 → 현재 요청 키와 다르면 아직 불러오는 중
  const [chart, setChart] = useState({ key: '', data: null, error: '' })

  const quote = useStockStore((state) => state.quotes[symbol])
  const feedMode = useStockStore((state) => state.feedMode)
  const setFeedMode = useStockStore((state) => state.setFeedMode)
  const liveCandle = useStockStore((state) => state.liveCandle)
  const setChartSubscription = useStockStore((state) => state.setChartSubscription)

  const showCandle = chartType === 'candle'
  const seriesKey = `${symbol}:${interval}`
  const requestKey = `${seriesKey}:${refreshKey}`

  // 초·분봉은 Push 로 진행 중인 봉을 받는다
  useEffect(() => {
    setChartSubscription(isIntraday(interval) ? { symbol, interval } : null)
    return () => setChartSubscription(null)
  }, [symbol, interval, setChartSubscription])

  useEffect(() => {
    const controller = new AbortController()
    const key = `${symbol}:${interval}`

    stockApi.getCandles(symbol, interval, { signal: controller.signal })
      .then((data) => setChart({ key, data, error: '' }))
      .catch((error) => {
        if (error.name !== 'AbortError') setChart((current) => ({ key, data: current.key === key ? current.data : null, error: error.message }))
      })

    return () => controller.abort()
  }, [symbol, interval, refreshKey])

  // 초·분봉 다시 조회: 응답을 받은 뒤 정해진 시간 후에 (Polling 2초, Push 1분)
  useEffect(() => {
    if (!isIntraday(interval) || chart.key !== seriesKey) return undefined
    const timer = setTimeout(() => setRefreshKey((k) => k + 1), feedMode === 'poll' ? POLL_REFRESH_MS : PUSH_REFRESH_MS)
    return () => clearTimeout(timer)
  }, [interval, feedMode, chart, seriesKey])

  const points = useMemo(() => {
    const base = chart.key === seriesKey && chart.data ? chart.data.points : []
    if (interval === '1d') return marketOpen ? mergeDailyQuote(base, quote) : base
    if (isIntraday(interval) && liveCandle?.symbol === symbol && liveCandle?.interval === interval) {
      return mergeCandle(base, liveCandle.data)
    }
    return base
  }, [chart, seriesKey, interval, marketOpen, quote, liveCandle, symbol])

  const { ref: zoomRef, start, end, zoomed, reset } = useChartZoom(points.length, showCandle ? DEFAULT_CANDLES : undefined)
  const visible = useMemo(() => points.slice(start, end), [points, start, end])
  const multiDay = useMemo(() => {
    if (!visible.length || !interval.endsWith('m')) return false
    return String(visible[0].time).slice(0, 10) !== String(visible[visible.length - 1].time).slice(0, 10)
  }, [visible, interval])

  const stats = periodStats(visible)
  const selected = INTERVALS.find((i) => i.value === interval)
  const loading = chart.key !== seriesKey && !chart.error
  const hasError = chart.key === seriesKey && chart.error && points.length === 0
  const selectInterval = (value) => {
    setInterval(value)
    reset()
  }

  const statusText = quote?.source === 'DEMO'
    ? '모의 체결 반영 중'
    : !marketOpen ? '장 운영 시간 외 · 새 체결 없음'
      : inClosingAuction() ? '장 마감 동시호가 중 (15:20~15:30) · 체결 없음'
        : isIntraday(interval) ? '실시간 체결 반영 중' : '현재가로 오늘 봉 갱신'

  return (
    <section className='panel chart-panel'>
      <div className='panel-heading'>
        <h2>주가 차트</h2>
        <div className='mode-toggle' role='group' aria-label='시세 전달 방식'>
          {[['push', 'Push'], ['poll', 'Polling']].map(([value, label]) => (
            <button
              key={value}
              type='button'
              className={hydrated && feedMode === value ? 'active' : ''}
              aria-pressed={hydrated && feedMode === value}
              onClick={() => setFeedMode(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      <div className='chart-container'>
        <div className='chart-controls'>
          <div className='stream-mode' role='group' aria-label='차트 종류'>
            <button type='button' className={!showCandle ? 'active' : ''} aria-pressed={!showCandle} onClick={() => setChartType('line')}>
              선 차트
            </button>
            <span className='tab-separator' aria-hidden='true'>·</span>
            <button type='button' className={showCandle ? 'active' : ''} aria-pressed={showCandle} onClick={() => setChartType('candle')}>
              캔들 차트
            </button>
          </div>

          <div className='range-tabs' role='group' aria-label='차트 주기'>
            <IntradayPicker interval={interval} onSelect={selectInterval} />
            {PERIOD_INTERVALS.map((item) => (
              <Fragment key={item.value}>
                <span className='tab-separator' aria-hidden='true'>·</span>
                <button
                  type='button'
                  className={interval === item.value ? 'active' : ''}
                  aria-pressed={interval === item.value}
                  onClick={() => selectInterval(item.value)}
                >
                  {item.label}
                </button>
              </Fragment>
            ))}
          </div>
        </div>

        <div className='stream-status'>
          <span>
            {selected.label} · {sourceNote(interval)}
            {chart.data?.source === 'DEMO' && <span className='badge demo'>모의 데이터</span>}
          </span>
          <span>{statusText}</span>
          <span className='chart-zoom-note'>
            마우스 휠로 확대·축소
            {zoomed && <button type='button' onClick={reset}>처음 크기로</button>}
          </span>
        </div>

        {stats && (
          <>
            <div className='chart-summary'>
              <strong>{formatPrice(stats.last)}</strong>
              <span className={directionClass(stats.changeRate)}>{formatRate(stats.changeRate)}</span>
            </div>
            <dl className='period-stats'>
              <div><dt>구간 시작</dt><dd>{formatPrice(stats.first)}</dd></div>
              <div><dt>구간 고가</dt><dd>{formatPrice(stats.high)}</dd></div>
              <div><dt>구간 저가</dt><dd>{formatPrice(stats.low)}</dd></div>
              <div><dt>구간 등락</dt><dd className={directionClass(stats.changeRate)}>{formatRate(stats.changeRate)}</dd></div>
            </dl>
          </>
        )}

        <div className='chart-area' ref={zoomRef}>
          {loading ? (
            <div className='chart-placeholder'>차트 데이터를 불러오는 중입니다.</div>
          ) : hasError ? (
            <div className='chart-placeholder error'>
              <strong>차트를 불러오지 못했습니다.</strong>
              <span>{chart.error}</span>
              <button type='button' onClick={() => setRefreshKey((k) => k + 1)}>다시 시도</button>
            </div>
          ) : points.length === 0 ? (
            <div className='chart-placeholder'>
              <strong>{isSeconds(interval) ? '아직 수신한 체결이 없습니다.' : '표시할 데이터가 없습니다.'}</strong>
              {isSeconds(interval) && <span>초봉은 서버가 연결된 뒤 받은 체결로 만듭니다. 체결이 없는 구간에는 봉을 만들지 않습니다.</span>}
            </div>
          ) : showCandle ? (
            <CandleChart points={visible} interval={interval} multiDay={multiDay} />
          ) : (
            <PriceLineChart points={visible} interval={interval} multiDay={multiDay} />
          )}
        </div>
      </div>
    </section>
  )
}
