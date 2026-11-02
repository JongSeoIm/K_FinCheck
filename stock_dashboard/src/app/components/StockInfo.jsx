'use client'

import { FaRegStar, FaStar } from 'react-icons/fa'
import useHydrated from '@/app/hooks/useHydrated'
import useStockData from '@/app/hooks/useStockData'
import useStockStore from '@/app/store/useStockStore'

function formatNumber(value, digits = 0) {
  if (value == null) return '—'
  return Number(value).toLocaleString('ko-KR', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

export default function StockInfo({ stock }) {
  const hydrated = useHydrated()
  const { data, loading, error } = useStockData(stock.symbol)
  const watchlist = useStockStore((state) => state.watchlist)
  const addToWatchlist = useStockStore((state) => state.addToWatchlist)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)

  const isSaved = hydrated && watchlist.includes(stock.symbol)
  const displayName = data?.stockName || stock.name || stock.symbol
  const displayMarket = data?.marketName || stock.market || 'KOSPI'
  const displayPrice = data?.price ?? stock.price ?? 0
  const displayChangeRate = data?.changeRate ?? stock.change ?? 0
  const priceChange = data?.priceChange ?? 0
  const isRising = displayChangeRate >= 0
  const isZero = displayChangeRate === 0

  const metrics = [
    ['시가', data?.open != null ? `${formatNumber(data.open)}원` : null],
    ['고가', data?.high != null ? `${formatNumber(data.high)}원` : null],
    ['저가', data?.low != null ? `${formatNumber(data.low)}원` : null],
    ['누적거래량', data?.volume != null ? `${formatNumber(data.volume)}주` : null],
    ['데이터 출처', data?.source ? `${data.source} 실전시세` : '조회 대기'],
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
          onClick={() => isSaved ? removeFromWatchlist(stock.symbol) : addToWatchlist(stock.symbol)}
          disabled={!hydrated}
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
            <strong>{formatNumber(displayPrice)}원</strong>
          )}
          <p className={isZero ? '' : isRising ? 'positive' : 'negative'}>
            {!isZero && (isRising ? '+' : '')}{formatNumber(priceChange)}원
            <span>({!isZero && (isRising ? '+' : '')}{formatNumber(displayChangeRate, 2)}%)</span>
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
    </section>
  )
}
