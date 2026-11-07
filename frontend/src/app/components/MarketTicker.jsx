'use client'

import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'
import { directionClass, formatNumber, formatRate } from '@/app/lib/format'

// 목업처럼 한 줄을 채울 만큼 종목을 반복한 묶음을 두 번 이어 붙여 끊김 없이 흐르게 한다
const MIN_ITEMS = 8

// 지원 종목의 현재가·등락률 (실제 서버 시세)
export default function MarketTicker({ stocks, activeSymbol, onSelect }) {
  const hydrated = useHydrated()
  const quotes = useStockStore((state) => state.quotes)
  const watchlist = useStockStore((state) => state.watchlist)

  if (stocks.length === 0) {
    return <section className='ticker-bar ticker-empty'>지원 종목을 불러오는 중입니다.</section>
  }

  const repeat = Math.ceil(MIN_ITEMS / stocks.length)
  const items = Array.from({ length: repeat }, () => stocks).flat()

  // copyIndex 0 = 실제로 누르는 묶음, 그 외 = 이어지는 모습을 위한 복사본 (화면 낭독·탭 이동에서 제외)
  const renderGroup = (copyIndex) => (
    <div className='ticker-group' aria-hidden={copyIndex > 0 ? 'true' : undefined}>
      {items.map((stock, i) => {
        const quote = quotes[stock.symbol]
        const saved = hydrated && watchlist.some((w) => w.symbol === stock.symbol)
        const dir = directionClass(quote?.changeRate)
        const primary = copyIndex === 0 && i < stocks.length
        return (
          <button
            key={`${copyIndex}-${i}-${stock.symbol}`}
            type='button'
            tabIndex={primary ? 0 : -1}
            className={`ticker-item ${activeSymbol === stock.symbol ? 'active' : ''}`}
            aria-label={primary ? `${stock.name} ${quote ? `${formatNumber(quote.price)}원 ${formatRate(quote.changeRate)}` : '시세 대기'}` : undefined}
            aria-current={primary && activeSymbol === stock.symbol ? 'true' : undefined}
            onClick={() => onSelect(stock)}
          >
            {saved && <span className='ticker-star' aria-hidden='true'>★</span>}
            <span className='ticker-name'>{stock.name}</span>
            {/* key를 수신 시각으로 두어 값이 바뀔 때마다 깜빡임 효과를 다시 실행 */}
            <strong key={quote?.receivedAt} className={`ticker-price flash-${dir}`}>
              {quote ? formatNumber(quote.price) : '—'}
            </strong>
            <small className={dir}>{quote ? formatRate(quote.changeRate) : ''}</small>
          </button>
        )
      })}
    </div>
  )

  return (
    <section className='ticker-bar' aria-label='지원 종목 시세'>
      <div className='ticker-track'>
        {renderGroup(0)}
        {renderGroup(1)}
      </div>
    </section>
  )
}
