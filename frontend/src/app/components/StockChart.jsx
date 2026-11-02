'use client'

import dynamic from 'next/dynamic'
import { useEffect, useMemo, useState } from 'react'
import { stockApi } from '@/api/stockApi'
import useStockStore from '@/app/store/useStockStore'
import { mergeLiveQuote, periodStats } from '@/app/lib/chartData'
import { directionClass, formatPrice, formatRate } from '@/app/lib/format'
import PriceLineChart from './PriceLineChart'

// Lightweight Charts는 브라우저에서만 동작하므로 서버 렌더링에서 제외
const CandleChart = dynamic(() => import('./CandleChart'), {
  ssr: false,
  loading: () => <div className='chart-placeholder'>차트를 준비하는 중입니다.</div>,
})

const RANGES = [
  { value: 'LIVE', label: '실시간', description: '서버에서 받은 실시간 시세 누적' },
  { value: '1D', label: '1일', description: '최근 거래일 1분봉' },
  { value: '1W', label: '1주', description: '일봉' },
  { value: '1M', label: '1개월', description: '일봉' },
  { value: '1Y', label: '1년', description: '일봉' },
]
const DAILY_RANGES = ['1W', '1M', '1Y']
const MINUTE_REFRESH_MS = 60000

export default function StockChart({ symbol, marketOpen }) {
  const [range, setRange] = useState('1D')
  const [chartType, setChartType] = useState('line')
  const [refreshKey, setRefreshKey] = useState(0)
  // 응답에 요청 키를 함께 저장 → 현재 요청 키와 다르면 아직 불러오는 중
  const [chart, setChart] = useState({ key: '', data: null, error: '' })
  const requestKey = `${symbol}:${range}:${refreshKey}`

  const quote = useStockStore((state) => state.quotes[symbol])
  const ticks = useStockStore((state) => state.ticks[symbol])

  const candleAvailable = DAILY_RANGES.includes(range)
  const showCandle = candleAvailable && chartType === 'candle'

  // 기간별 차트 조회 (실시간 탭은 서버 조회 없이 누적 시세 사용)
  useEffect(() => {
    if (range === 'LIVE') return undefined
    const controller = new AbortController()
    const key = `${symbol}:${range}:${refreshKey}`

    stockApi.getChart(symbol, range, { signal: controller.signal })
      .then((data) => setChart({ key, data, error: '' }))
      .catch((error) => {
        if (error.name !== 'AbortError') setChart({ key, data: null, error: error.message })
      })

    return () => controller.abort()
  }, [symbol, range, refreshKey])

  // 장중에는 1일(분봉) 차트를 1분마다 다시 조회
  useEffect(() => {
    if (range !== '1D' || !marketOpen) return undefined
    const timer = setInterval(() => setRefreshKey((k) => k + 1), MINUTE_REFRESH_MS)
    return () => clearInterval(timer)
  }, [range, marketOpen])

  // 표시할 데이터: 장중에는 최신 시세로 마지막 봉을 갱신
  const points = useMemo(() => {
    if (range === 'LIVE') {
      return (ticks || []).map((t) => ({ time: t.time, close: t.price }))
    }
    const base = chart.data?.range === range && chart.data?.symbol === symbol ? chart.data.points : []
    return marketOpen ? mergeLiveQuote(base, chart.data?.interval, quote) : base
  }, [range, ticks, chart.data, symbol, marketOpen, quote])

  const interval = range === 'LIVE' ? 'live' : chart.data?.interval
  const stats = periodStats(points, range === '1D' ? quote?.previousClose : undefined)
  const selectedRange = RANGES.find((r) => r.value === range)
  // 1분 주기 재조회 중에는 기존 차트를 그대로 보여준다
  const loading = range !== 'LIVE' && chart.key !== requestKey && points.length === 0

  return (
    <section className='panel chart-panel'>
      <div className='panel-heading chart-heading'>
        <h2>주가 차트</h2>
        <div className='chart-controls'>
          <div className='segmented' role='group' aria-label='차트 기간'>
            {RANGES.map((r) => (
              <button
                key={r.value}
                type='button'
                className={range === r.value ? 'active' : ''}
                aria-pressed={range === r.value}
                onClick={() => setRange(r.value)}
              >
                {r.label}
              </button>
            ))}
          </div>
          <div className='segmented' role='group' aria-label='차트 종류'>
            <button
              type='button'
              className={!showCandle ? 'active' : ''}
              aria-pressed={!showCandle}
              onClick={() => setChartType('line')}
            >
              선
            </button>
            <button
              type='button'
              className={showCandle ? 'active' : ''}
              aria-pressed={showCandle}
              title={candleAvailable ? '일봉 캔들 차트' : '캔들은 일봉이라 1개월 차트로 전환됩니다'}
              onClick={() => {
                // 캔들은 일봉 기간에서만 그리므로 실시간·1일에서 누르면 1개월로 바꾼다
                if (!candleAvailable) setRange('1M')
                setChartType('candle')
              }}
            >
              캔들
            </button>
          </div>
        </div>
      </div>

      <p className='chart-caption'>
        {selectedRange.label} · {selectedRange.description}
        {chart.data?.source === 'DEMO' && range !== 'LIVE' && <span className='badge demo'>모의 데이터</span>}
      </p>

      {stats && (
        <dl className='period-stats'>
          <div><dt>기간 시작</dt><dd>{formatPrice(stats.first)}</dd></div>
          <div><dt>기간 고가</dt><dd>{formatPrice(stats.high)}</dd></div>
          <div><dt>기간 저가</dt><dd>{formatPrice(stats.low)}</dd></div>
          <div>
            <dt>{range === '1D' ? '전일 대비' : '기간 등락'}</dt>
            <dd className={directionClass(stats.changeRate)}>{formatRate(stats.changeRate)}</dd>
          </div>
        </dl>
      )}

      <div className='chart-area'>
        {loading ? (
          <div className='chart-placeholder'>차트 데이터를 불러오는 중입니다.</div>
        ) : chart.error && range !== 'LIVE' ? (
          <div className='chart-placeholder error'>
            <strong>차트를 불러오지 못했습니다.</strong>
            <span>{chart.error}</span>
            <button type='button' onClick={() => setRefreshKey((k) => k + 1)}>다시 시도</button>
          </div>
        ) : points.length === 0 ? (
          <div className='chart-placeholder'>
            {range === 'LIVE'
              ? '아직 받은 실시간 시세가 없습니다. 시세가 들어오면 여기에 쌓입니다.'
              : '표시할 데이터가 없습니다.'}
          </div>
        ) : showCandle ? (
          <CandleChart points={points} />
        ) : (
          <PriceLineChart points={points} interval={interval} />
        )}
      </div>
    </section>
  )
}
