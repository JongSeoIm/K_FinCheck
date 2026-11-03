'use client'

import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'
import {
  directionClass, formatClock, formatPrice, formatRate, formatSignedPrice, formatVolume,
} from '@/app/lib/format'

// 목업의 별 아이콘(react-icons FaStar·FaRegStar)과 같은 모양을 SVG로 그린다
function StarIcon({ filled }) {
  return (
    <svg viewBox='0 0 24 24' aria-hidden='true' fill={filled ? 'currentColor' : 'none'} stroke='currentColor' strokeWidth='2' strokeLinejoin='round'>
      <path d='M12 2.8l2.9 5.9 6.5.9-4.7 4.6 1.1 6.5L12 17.6l-5.8 3.1 1.1-6.5-4.7-4.6 6.5-.9z' />
    </svg>
  )
}

// 선택 종목의 현재가·등락·시가·고가·저가·전일 종가
export default function StockInfo({ stock }) {
  const hydrated = useHydrated()
  const quote = useStockStore((state) => state.quotes[stock.symbol])
  const watchlist = useStockStore((state) => state.watchlist)
  const addToWatchlist = useStockStore((state) => state.addToWatchlist)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)

  const saved = hydrated && watchlist.some((w) => w.symbol === stock.symbol)
  const dir = directionClass(quote?.changeAmount)

  const metrics = [
    ['시가', formatPrice(quote?.open)],
    ['고가', formatPrice(quote?.high)],
    ['저가', formatPrice(quote?.low)],
    ['전일 종가', formatPrice(quote?.previousClose)],
    ['거래량', formatVolume(quote?.cumulativeVolume)],
  ]

  return (
    <section className='panel quote-panel'>
      <div className='quote-heading'>
        <div>
          <h2>{stock.name}</h2>
          <p>
            {stock.symbol} · {stock.market || '국내 주식'}
            {quote?.source === 'DEMO' && <span className='badge demo'>모의 데이터</span>}
            {quote?.source === 'KIS' && <span className='badge'>KIS</span>}
          </p>
        </div>
        <button
          type='button'
          className={`watch-toggle ${saved ? 'saved' : ''}`}
          disabled={!hydrated}
          aria-pressed={saved}
          onClick={() => (saved ? removeFromWatchlist(stock.symbol) : addToWatchlist(stock))}
        >
          <StarIcon filled={saved} />
          {saved ? '관심 종목 해제' : '관심 종목 추가'}
        </button>
      </div>

      {quote ? (
        <div className='quote-body'>
          <div className='headline'>
            <span className='label'>현재가</span>
            <strong key={quote.receivedAt} className={`headline-price flash-${dir}`}>
              {formatPrice(quote.price)}
            </strong>
            <p className={dir}>
              <span>{formatSignedPrice(quote.changeAmount)}</span>
              <span>{formatRate(quote.changeRate)}</span>
            </p>
            <small className='updated'>마지막 갱신 {formatClock(quote.receivedAt)}</small>
          </div>

          <dl className='metrics'>
            {metrics.map(([label, value]) => (
              <div key={label}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        </div>
      ) : (
        <div className='empty-state'>
          <strong>시세 데이터가 아직 없습니다.</strong>
          <p>서버에서 시세를 받으면 자동으로 표시됩니다.</p>
          <p>계속 비어 있다면 서버 로그에서 KIS 오류 메시지를 확인하세요.</p>
        </div>
      )}
    </section>
  )
}
