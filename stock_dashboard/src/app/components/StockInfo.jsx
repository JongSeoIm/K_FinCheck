'use client'

import { FaRegStar, FaStar } from 'react-icons/fa'
import useHydrated from '@/app/hooks/useHydrated'
import useStockData from '@/app/hooks/useStockData'
import useStockStore from '@/app/store/useStockStore'
import QuoteStatus from './QuoteStatus'
import useAuthStore from '../store/useAuthStore'

function formatNumber(value, digits = 0) {
  if (value == null) return '—'
  return Number(value).toLocaleString('ko-KR', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

export default function StockInfo({ stock, liveQuote }) {
  const hydrated = useHydrated()
  const { data: restData, loading: restLoading, error: restError } = useStockData(stock.symbol)
  const data = liveQuote ? { ...restData, ...liveQuote, source: 'KIS WebSocket' } : restData
  const loading = restLoading && !liveQuote
  const error = liveQuote ? null : restError
  const watchlist = useStockStore((state) => state.watchlist)
  const addToWatchlist = useStockStore((state) => state.addToWatchlist)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)
  const saving = useStockStore((state) => state.saving)
  const user = useAuthStore(state => state.user)

  const isSaved = hydrated && watchlist.includes(stock.symbol)
  const displayName = data?.stockName || stock.name || stock.symbol
  const displayMarket = data?.marketName || stock.market || 'KOSPI'
  const displayPrice = data?.price ?? null
  const displayChangeRate = data?.changeRate ?? null
  const priceChange = data?.priceChange ?? null
  const isRising = displayChangeRate >= 0
  const isZero = displayChangeRate === 0

  const metrics = [
    ['시가', data?.open != null ? `${formatNumber(data.open)}원` : null],
    ['고가', data?.high != null ? `${formatNumber(data.high)}원` : null],
    ['저가', data?.low != null ? `${formatNumber(data.low)}원` : null],
    ['누적거래량', data?.volume != null ? `${formatNumber(data.volume)}주` : null],
  ]

  return (
    <section className='panel quote-overview'>
      <div className='quote-heading'>
        <div>
          <h2>{displayName}</h2>
          <p>{stock.symbol} · {displayMarket} · 보통주</p>
        </div>
        <button
          className={`watch-toggle ${isSaved ? 'saved' : ''}`}
          type='button'
          onClick={() => {
            if (!user) { window.location.assign(`/login?next=${encodeURIComponent(`/?symbol=${stock.symbol}`)}`); return }
            if (isSaved) removeFromWatchlist(stock.symbol)
            else addToWatchlist(stock.symbol)
          }}
          disabled={!hydrated || saving}
        >
          {isSaved ? <FaStar aria-hidden='true' /> : <FaRegStar aria-hidden='true' />}
          {isSaved ? '관심 종목 해제' : '관심 종목 추가'}
        </button>
      </div>

      <div className='quote-body'>
        <div className='headline-price'>
          <span>현재가</span>
          {loading ? (
            <span className='price-skeleton' />
          ) : (
            <strong>{displayPrice == null ? '—' : `${formatNumber(displayPrice)}원`}</strong>
          )}
          <p className={displayChangeRate == null || isZero ? '' : isRising ? 'positive' : 'negative'}>
            {priceChange == null ? '—' : `${priceChange > 0 ? '+' : ''}${formatNumber(priceChange)}원`}
            {displayChangeRate != null && <span>({displayChangeRate > 0 ? '+' : ''}{formatNumber(displayChangeRate, 2)}%)</span>}
          </p>
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
      <QuoteStatus quote={liveQuote} fetchedAt={restData?.fetchedAt} source={data?.source === 'KIS WebSocket' ? 'KIS 실시간' : data?.source ? `${data.source} 시세` : null} />
    </section>
  )
}
