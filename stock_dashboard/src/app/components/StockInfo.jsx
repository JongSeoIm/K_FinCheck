'use client'

import useStockData from '@/app/hooks/useStockData'
import { changeRateClass } from '../lib/market'
import QuoteStatus from './QuoteStatus'
import WatchStar from './WatchStar'

function formatNumber(value, digits = 0) {
  if (value == null) return '—'
  return Number(value).toLocaleString('ko-KR', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

export default function StockInfo({ stock, liveQuote }) {
  const { data: restData, loading: restLoading, error: restError } = useStockData(stock.symbol)
  const data = liveQuote ? { ...restData, ...liveQuote, source: 'KIS WebSocket' } : restData
  const loading = restLoading && !liveQuote
  const error = liveQuote ? null : restError
  const displayName = data?.stockName || stock.name || stock.symbol
  const displayMarket = data?.marketName || stock.market || 'KOSPI'
  const displayPrice = data?.price ?? null
  const displayChangeRate = data?.changeRate ?? null
  const priceChange = data?.priceChange ?? null

  const metrics = [
    ['시가', data?.open != null ? `${formatNumber(data.open)}원` : null],
    ['고가', data?.high != null ? `${formatNumber(data.high)}원` : null],
    ['저가', data?.low != null ? `${formatNumber(data.low)}원` : null],
    ['누적거래량', data?.volume != null ? `${formatNumber(data.volume)}주` : null],
  ]

  return (
    <section className='panel quote-overview'>
      <div className='quote-heading'>
        <div className='quote-identity'>
          <h2>{displayName}</h2>
          <WatchStar symbol={stock.symbol} name={displayName} returnTo={`/?symbol=${stock.symbol}`} />
          <p>{stock.symbol} · {displayMarket} · 보통주</p>
        </div>
        <QuoteStatus compact quote={liveQuote} fetchedAt={restData?.fetchedAt}
          source={data?.source === 'KIS WebSocket' ? 'KIS 실시간' : data?.source ? `${data.source} 시세` : null} />
      </div>

      <div className='quote-body'>
        <div className='headline-price'>
          <span className='sr-only'>현재가</span>
          {loading ? (
            <span className='price-skeleton' />
          ) : (
            <strong>{displayPrice == null ? '—' : `${formatNumber(displayPrice)}원`}</strong>
          )}
          <p><span className={changeRateClass(displayChangeRate)}>
            {priceChange == null ? '—' : `${priceChange > 0 ? '+' : ''}${formatNumber(priceChange)}원`}
            {displayChangeRate != null && ` (${displayChangeRate > 0 ? '+' : ''}${formatNumber(displayChangeRate, 2)}%)`}
          </span></p>
          {error && <small className='inline-warning'>{error}</small>}
        </div>

        <dl className='quote-metrics'>
          {metrics.map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value == null ? '—' : value}</dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  )
}
