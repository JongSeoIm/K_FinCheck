'use client'

import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'
import { changeRateClass, marketStocks } from '@/app/lib/market'
import useStockData from '@/app/hooks/useStockData'
import QuoteStatus from './QuoteStatus'
import useAuthStore from '../store/useAuthStore'
import WatchStar from './WatchStar'
import StockIcon from './StockIcon'

function WatchlistRow({ stock, active, liveQuote, onSelect }) {
  const { data, error } = useStockData(stock.symbol, !liveQuote)
  const quote = liveQuote || data
  const price = Number.isFinite(quote?.price) ? quote.price : null
  const rate = Number.isFinite(quote?.changeRate) ? quote.changeRate : null
  const name = data?.stockName || stock.name
  return <li className={active ? 'active' : ''}>
    <button className='watchlist-select' type='button' onClick={() => onSelect(stock)}>
      <span className='watch-name'><StockIcon symbol={stock.symbol} /><strong>{name}</strong><small>{stock.symbol}</small></span>
      <span className='watch-price' title={error && !liveQuote ? error : undefined}>
        <strong>{price == null ? '—' : `${price.toLocaleString('ko-KR')}원`}</strong>
        <small className={changeRateClass(rate)}>
          {rate == null ? '—' : `${rate > 0 ? '+' : ''}${rate.toFixed(2)}%`}
        </small>
      </span>
    </button>
    <WatchStar symbol={stock.symbol} name={name} />
  </li>
}

export default function WatchlistPanel({ activeSymbol, onSelectStock, trades = {} }) {
  const hydrated = useHydrated()
  const watchlist = useStockStore((state) => state.watchlist)
  const user = useAuthStore(state => state.user)
  const error = useStockStore(state => state.watchlistError)

  return (
    <section className='panel watchlist-panel'>
      <div className='panel-heading'>
        <h2>관심 종목</h2>
      </div>

      {!hydrated ? (
        <div className='skeleton-list'>
          {[1, 2].map((item) => <span key={item} className='skeleton-row compact' />)}
        </div>
      ) : watchlist.length === 0 ? (
        <div className='empty-state'>
          <strong>{user ? '아직 저장한 종목이 없습니다.' : '로그인 후 관심 종목을 저장해 보세요.'}</strong>
          <p>{user ? '종목 옆 별표를 누르면 여기에 보관됩니다.' : '계정별로 관심 종목을 보관합니다.'}</p>
        </div>
      ) : (
        <ul className='watchlist'>
          {watchlist.map((symbol) => {
            const stock = marketStocks.find((item) => item.symbol === symbol) || { symbol, name: symbol }
            return (
              <WatchlistRow key={symbol} stock={stock} active={activeSymbol === symbol}
                liveQuote={trades[symbol]} onSelect={onSelectStock} />
            )
          })}
        </ul>
      )}
      {error && <p className='financial-state' role='alert'>{error}</p>}
      {hydrated && watchlist.length > 0 && <QuoteStatus quote={Object.values(trades)
        .filter(quote => watchlist.includes(quote.stockCode) && quote.exchangeTime)
        .sort((a, b) => Date.parse(b.exchangeTime) - Date.parse(a.exchangeTime))[0]} />}
    </section>
  )
}
