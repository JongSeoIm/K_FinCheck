'use client'

import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'
import { directionClass, formatNumber, formatRate } from '@/app/lib/format'

// 지원 종목의 현재가·등락률 (실제 서버 시세)
export default function MarketTicker({ stocks, activeSymbol, onSelect }) {
  const hydrated = useHydrated()
  const quotes = useStockStore((state) => state.quotes)
  const watchlist = useStockStore((state) => state.watchlist)

  if (stocks.length === 0) {
    return <section className='ticker-bar ticker-empty'>지원 종목을 불러오는 중입니다.</section>
  }

  return (
    <section className='ticker-bar' aria-label='지원 종목 시세'>
      {stocks.map((stock) => {
        const quote = quotes[stock.symbol]
        const saved = hydrated && watchlist.some((w) => w.symbol === stock.symbol)
        const dir = directionClass(quote?.changeRate)
        return (
          <button
            key={stock.symbol}
            type='button'
            className={`ticker-item ${activeSymbol === stock.symbol ? 'active' : ''}`}
            aria-label={`${stock.name} ${quote ? `${formatNumber(quote.price)}원 ${formatRate(quote.changeRate)}` : '시세 대기'}`}
            aria-current={activeSymbol === stock.symbol ? 'true' : undefined}
            onClick={() => onSelect(stock)}
          >
            <span className='ticker-name'>
              {saved && <span className='ticker-star' aria-label='관심 종목'>★</span>}
              {stock.name}
            </span>
            {/* key를 갱신 시각으로 두어 값이 바뀔 때마다 깜빡임 효과를 다시 실행 */}
            <strong key={quote?.updatedAt} className={`ticker-price flash-${dir}`}>
              {quote ? formatNumber(quote.price) : '—'}
            </strong>
            <small className={dir}>{quote ? formatRate(quote.changeRate) : ''}</small>
          </button>
        )
      })}
    </section>
  )
}
