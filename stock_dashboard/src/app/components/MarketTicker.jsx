'use client'

import { useEffect, useRef, useState } from 'react'
import { FaStar } from 'react-icons/fa'
import useHydrated from '../hooks/useHydrated'
import { marketStocks } from '../lib/market'
import useStockStore from '../store/useStockStore'

const tickerStocks = marketStocks.slice(0, 8)

export default function MarketTicker({ activeSymbol, onSelectStock, trades }) {
  const hydrated = useHydrated()
  const watchlist = useStockStore((state) => state.watchlist)
  const [priceFlashes, setPriceFlashes] = useState({})
  const previousPricesRef = useRef({})
  const flashTimersRef = useRef({})

  useEffect(() => {
    tickerStocks.forEach((stock) => {
      const symbol = stock.symbol
      const nextPrice = Number(trades[symbol]?.price)
      if (!Number.isFinite(nextPrice)) return

      const previousPrice = previousPricesRef.current[symbol]
      previousPricesRef.current[symbol] = nextPrice

      if (previousPrice === undefined || previousPrice === nextPrice) return

      const direction = nextPrice > previousPrice ? 'up' : 'down'
      setPriceFlashes((current) => ({ ...current, [symbol]: direction }))

      clearTimeout(flashTimersRef.current[symbol])
      flashTimersRef.current[symbol] = setTimeout(() => {
        setPriceFlashes((current) => {
          const next = { ...current }
          delete next[symbol]
          return next
        })
      }, 650)
    })
  }, [trades])

  useEffect(() => () => {
    Object.values(flashTimersRef.current).forEach(clearTimeout)
  }, [])

  const renderTickerGroup = (copyIndex) => (
    <div
      className='ticker-group'
      aria-hidden={copyIndex === 1 ? 'true' : undefined}
    >
      {tickerStocks.map((stock) => {
        const livePrice = trades[stock.symbol]?.price
        const isSaved = hydrated && watchlist.includes(stock.symbol)
        const flashDirection = priceFlashes[stock.symbol]
        const flashClass = flashDirection === 'up'
          ? 'animate-[ticker-flash-up_650ms_ease-out]'
          : flashDirection === 'down'
            ? 'animate-[ticker-flash-down_650ms_ease-out]'
            : ''

        const displayPrice = livePrice != null ? Number(livePrice) : stock.price

        return (
          <button
            key={`${copyIndex}-${stock.symbol}`}
            type='button'
            tabIndex={copyIndex === 1 ? -1 : 0}
            className={`ticker-item ${activeSymbol === stock.symbol ? 'active' : ''} ${flashClass}`}
            onClick={() => onSelectStock(stock)}
          >
            {isSaved && <FaStar className='ticker-star' aria-label='관심 종목' />}
            <span>{stock.name || stock.symbol}</span>
            {displayPrice > 0 ? (
              <strong>₩{displayPrice.toLocaleString('ko-KR')}</strong>
            ) : (
              <small>{stock.symbol}</small>
            )}
            <small className={stock.change >= 0 ? 'positive' : 'negative'}>
              {stock.change >= 0 ? '+' : ''}{stock.change.toFixed(2)}%
            </small>
          </button>
        )
      })}
    </div>
  )

  return (
    <section className='ticker-bar' aria-label='주요 종목 시세'>
      <div className='ticker-track animate-[ticker-scroll_36s_linear_infinite]'>
        {renderTickerGroup(0)}
        {renderTickerGroup(1)}
      </div>
    </section>
  )
}
