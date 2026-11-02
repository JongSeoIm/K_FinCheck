'use client'

import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'
import { directionClass, formatNumber, formatRate } from '@/app/lib/format'

// 관심 종목 (브라우저 저장, 새로고침해도 유지)
export default function WatchlistPanel({ activeSymbol, onSelect }) {
  const hydrated = useHydrated()
  const watchlist = useStockStore((state) => state.watchlist)
  const quotes = useStockStore((state) => state.quotes)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)

  return (
    <section className='panel watchlist-panel'>
      <div className='panel-heading'>
        <h2>관심 종목</h2>
        <span className='count'>{hydrated ? watchlist.length : 0}</span>
      </div>

      {!hydrated ? (
        <div className='skeleton-list'><span /><span /></div>
      ) : watchlist.length === 0 ? (
        <div className='empty-state'>
          <strong>저장한 종목이 없습니다.</strong>
          <p>종목 정보의 ‘관심 종목 추가’를 누르면 여기에 보관됩니다.</p>
        </div>
      ) : (
        <ul className='watchlist'>
          {watchlist.map((stock) => {
            const quote = quotes[stock.symbol]
            const dir = directionClass(quote?.changeRate)
            return (
              <li key={stock.symbol} className={activeSymbol === stock.symbol ? 'active' : ''}>
                <button type='button' className='watch-select' onClick={() => onSelect(stock)}>
                  <span className='watch-name'>
                    <strong>{stock.name}</strong>
                    <small>{stock.symbol}</small>
                  </span>
                  <span className='watch-price'>
                    <strong>{quote ? formatNumber(quote.price) : '—'}</strong>
                    <small className={dir}>{quote ? formatRate(quote.changeRate) : '시세 대기'}</small>
                  </span>
                </button>
                <button
                  type='button'
                  className='watch-remove'
                  onClick={() => removeFromWatchlist(stock.symbol)}
                  aria-label={`${stock.name} 관심 종목 삭제`}
                >
                  삭제
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
