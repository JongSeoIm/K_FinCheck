'use client'

import Link from 'next/link'
import useAuthStore from '@/app/store/useAuthStore'
import useStockStore from '@/app/store/useStockStore'
import { directionClass, formatNumber, formatRate } from '@/app/lib/format'

// 관심 종목 (계정별 서버 저장, 다시 로그인해도 유지)
export default function WatchlistPanel({ activeSymbol, onSelect }) {
  const user = useAuthStore((state) => state.user)
  const authChecked = useAuthStore((state) => state.checked)
  const watchlist = useStockStore((state) => state.watchlist)
  const status = useStockStore((state) => state.watchlistStatus)
  const error = useStockStore((state) => state.watchlistError)
  const quotes = useStockStore((state) => state.quotes)
  const loadWatchlist = useStockStore((state) => state.loadWatchlist)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)

  const loading = !authChecked || (user && (status === 'idle' || status === 'loading'))

  let body
  if (loading) {
    body = <div className='skeleton-list'><span /><span /></div>
  } else if (!user) {
    body = (
      <div className='empty-state'>
        <strong>로그인하면 관심 종목을 저장할 수 있어요.</strong>
        <p>계정에 저장되어 다른 브라우저에서 로그인해도 그대로 보여요. <Link href='/login'>로그인</Link></p>
      </div>
    )
  } else if (status === 'error') {
    body = (
      <div className='empty-state'>
        <strong>관심 종목을 불러오지 못했습니다.</strong>
        <p>{error}</p>
        <button type='button' className='watch-retry' onClick={loadWatchlist}>다시 시도</button>
      </div>
    )
  } else if (watchlist.length === 0) {
    body = (
      <div className='empty-state'>
        <strong>저장한 종목이 없습니다.</strong>
        <p>종목 정보의 ‘관심 종목 추가’를 누르면 여기에 보관됩니다.</p>
      </div>
    )
  } else {
    body = (
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
    )
  }

  return (
    <section className='panel watchlist-panel'>
      <div className='panel-heading'>
        <h2>관심 종목</h2>
        <span className='count'>{user ? watchlist.length : 0}</span>
      </div>
      {user && status === 'ready' && error && <p className='watch-error' role='alert'>{error}</p>}
      {body}
    </section>
  )
}
