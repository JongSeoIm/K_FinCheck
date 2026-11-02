'use client'

import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'
import { marketStocks } from '@/app/lib/market'

export default function WatchlistPanel({ activeSymbol, onSelectStock }) {
  const hydrated = useHydrated()
  const watchlist = useStockStore((state) => state.watchlist)
  const prices = useStockStore((state) => state.prices)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)

  return (
    <section className='panel watchlist-panel'>
      <div className='panel-heading'>
        <h2>관심 종목</h2>
        <span className='count-badge'>{hydrated ? watchlist.length : 0}</span>
      </div>

      {!hydrated ? (
        <div className='skeleton-list'>
          {[1, 2].map((item) => <span key={item} className='skeleton-row compact' />)}
        </div>
      ) : watchlist.length === 0 ? (
        <div className='empty-state'>
          <strong>아직 저장한 종목이 없습니다.</strong>
          <p>종목 상단의 ‘관심 추가’를 누르면 여기에 보관됩니다.</p>
        </div>
      ) : (
        <ul className='watchlist'>
          {watchlist.map((symbol) => {
            const stock = marketStocks.find((item) => item.symbol === symbol) || {
              symbol,
              name: symbol,
              price: prices[symbol] || 0,
              change: 0,
            }
            return (
              <li key={symbol} className={activeSymbol === symbol ? 'active' : ''}>
                <button className='watchlist-select' type='button' onClick={() => onSelectStock(stock)}>
                  <span>
                    <strong>{symbol}</strong>
                    <small>{stock.name}</small>
                  </span>
                  <span className='watch-price'>
                    {prices[symbol] ? `₩${Number(prices[symbol]).toLocaleString('ko-KR')}원` : '선택해서 조회'}
                  </span>
                </button>
                <button
                  className='watchlist-remove'
                  type='button'
                  onClick={() => removeFromWatchlist(symbol)}
                  aria-label={`${symbol} 관심 종목 삭제`}
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
