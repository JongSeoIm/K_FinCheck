'use client'

import dynamic from 'next/dynamic'
import { Fragment, useEffect, useMemo, useState } from 'react'
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
  { value: 'LIVE', label: '실시간', description: '받은 체결 누적' },
  { value: '1D', label: '1일', description: '1분 간격' },
  { value: '1W', label: '1주', description: '일별 종가' },
  { value: '1M', label: '1개월', description: '일별 종가' },
  { value: '1Y', label: '1년', description: '일별 종가' },
]
const DAILY_RANGES = ['1W', '1M', '1Y']
const MINUTE_REFRESH_MS = 60000

// 15:20~15:30 은 장 마감 동시호가라 연속 체결이 없다 (한국 시간 기준)
function inClosingAuction() {
  const [h, m] = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul' })
    .format(new Date()).split(':').map(Number)
  const minutes = h * 60 + m
  return minutes >= 15 * 60 + 20 && minutes < 15 * 60 + 30
}

export default function StockChart({ symbol, marketOpen }) {
  const [range, setRange] = useState('1D')
  const [chartType, setChartType] = useState('line')
  const [refreshKey, setRefreshKey] = useState(0)
  // 응답에 요청 키를 함께 저장 → 현재 요청 키와 다르면 아직 불러오는 중
  const [chart, setChart] = useState({ key: '', data: null, error: '' })

  const quote = useStockStore((state) => state.quotes[symbol])
  const ticks = useStockStore((state) => state.ticks[symbol])

  const candleAvailable = DAILY_RANGES.includes(range)
  const showCandle = candleAvailable && chartType === 'candle'
  // 선 차트는 /history, 캔들은 /candles 에서 받는다
  const source = showCandle ? 'candles' : 'history'
  const requestKey = `${symbol}:${source}:${range}:${refreshKey}`

  // 기간별 차트 조회 (실시간 탭은 서버 조회 없이 누적 시세 사용)
  useEffect(() => {
    if (range === 'LIVE') return undefined
    const controller = new AbortController()
    const key = `${symbol}:${source}:${range}:${refreshKey}`
    const request = source === 'candles' ? stockApi.getCandles : stockApi.getHistory

    request(symbol, range, { signal: controller.signal })
      .then((data) => setChart({ key, data, error: '' }))
      .catch((error) => {
        if (error.name !== 'AbortError') setChart({ key, data: null, error: error.message })
      })

    return () => controller.abort()
  }, [symbol, source, range, refreshKey])

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

  // 목업의 수신 상태 문구 자리: 지금 차트가 어떤 기준으로 갱신되는지
  const statusText = range === 'LIVE'
    ? (quote?.source === 'DEMO' ? '모의 체결 반영 중' : !marketOpen ? '장 운영 시간 외 · 새 체결 없음' : inClosingAuction() ? '장 마감 동시호가 중 (15:20~15:30) · 체결 없음' : '실시간 체결 반영 중')
    : range === '1D'
      ? (marketOpen ? '1분마다 분봉 갱신' : '최근 거래일 분봉')
      : '기간별 일봉 조회'

  return (
    <section className='panel chart-panel'>
      <div className='panel-heading'>
        <h2>실시간 주가 차트</h2>
      </div>

      <div className='chart-container'>
        <div className='chart-controls'>
          <div className='stream-mode' role='group' aria-label='차트 종류'>
            <button
              type='button'
              className={!showCandle ? 'active' : ''}
              aria-pressed={!showCandle}
              onClick={() => setChartType('line')}
            >
              선 차트
            </button>
            <span className='tab-separator' aria-hidden='true'>·</span>
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
              캔들 차트
            </button>
          </div>

          <div className='range-tabs' role='group' aria-label='차트 기간'>
            {RANGES.map((r, index) => (
              <Fragment key={r.value}>
                {index > 0 && <span className='tab-separator' aria-hidden='true'>·</span>}
                <button
                  type='button'
                  className={range === r.value ? 'active' : ''}
                  aria-pressed={range === r.value}
                  onClick={() => setRange(r.value)}
                >
                  {r.label}
                </button>
              </Fragment>
            ))}
          </div>
        </div>

        <div className='stream-status'>
          <span>
            {selectedRange.label} · {selectedRange.description}
            {chart.data?.source === 'DEMO' && range !== 'LIVE' && <span className='badge demo'>모의 데이터</span>}
          </span>
          <span>{statusText}</span>
        </div>

        {stats && (
          <>
            <div className='chart-summary'>
              <strong>{formatPrice(stats.last)}</strong>
              <span className={directionClass(stats.changeRate)}>{formatRate(stats.changeRate)}</span>
            </div>
            <dl className='period-stats'>
              <div><dt>기간 시작</dt><dd>{formatPrice(stats.first)}</dd></div>
              <div><dt>기간 고가</dt><dd>{formatPrice(stats.high)}</dd></div>
              <div><dt>기간 저가</dt><dd>{formatPrice(stats.low)}</dd></div>
              <div>
                <dt>{range === '1D' ? '전일 대비' : '기간 등락'}</dt>
                <dd className={directionClass(stats.changeRate)}>{formatRate(stats.changeRate)}</dd>
              </div>
            </dl>
          </>
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
          ) : range === 'LIVE' && points.length < 2 ? (
            // 실시간 탭은 화면을 연 뒤 받은 체결만 그린다. 1건뿐이면 평평한 선이 되어 고장처럼 보이므로 안내한다
            <div className='chart-placeholder'>
              <strong>{marketOpen && !inClosingAuction() ? '실시간 체결을 기다리는 중입니다.' : '지금은 새 체결이 들어오지 않는 시간입니다.'}</strong>
              <span>
                {marketOpen && !inClosingAuction()
                  ? '이 화면을 연 뒤 받은 체결이 2건 이상 쌓이면 그래프가 그려집니다.'
                  : '실시간 탭은 화면을 연 뒤 받은 체결만 그립니다. 오늘 흐름은 1일 탭에서 확인하세요.'}
              </span>
            </div>
          ) : points.length === 0 ? (
            <div className='chart-placeholder'>표시할 데이터가 없습니다.</div>
          ) : showCandle ? (
            <CandleChart points={points} />
          ) : (
            <PriceLineChart points={points} interval={interval} />
          )}
        </div>
      </div>
    </section>
  )
}
