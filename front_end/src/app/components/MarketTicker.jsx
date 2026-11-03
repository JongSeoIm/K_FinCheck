'use client'

import { useEffect, useRef, useState } from 'react'
import { FaStar } from 'react-icons/fa'
import useHydrated from '../hooks/useHydrated'
import { marketStocks } from '../lib/market'
import useStockStore from '../store/useStockStore'

// 티커에 보여 줄 종목 (KIS 호출 부담을 줄이려면 숫자를 줄이세요)
const tickerStocks = marketStocks.slice(0, 8)
const TICKER_CODES = tickerStocks.map((stock) => stock.symbol).join(',')
const REFRESH_MS = 10000

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')

export default function MarketTicker({ activeSymbol, onSelectStock }) {
  const hydrated = useHydrated()
  const watchlist = useStockStore((state) => state.watchlist)
  const [prices, setPrices] = useState({})
  const [failed, setFailed] = useState(false)
  const [priceFlashes, setPriceFlashes] = useState({})
  const previousPricesRef = useRef({})
  const flashTimersRef = useRef({})

  // Spring에서 여러 종목 시세를 10초마다 받아 옵니다.
  useEffect(() => {
    const controller = new AbortController()

    const load = async () => {
      try {
        const response = await fetch(`/api/kis/prices?codes=${TICKER_CODES}`, {
          signal: controller.signal,
          cache: 'no-store',
        })
        if (!response.ok) throw new Error('ticker error')
        setPrices(await response.json())
        setFailed(false)
      } catch (requestError) {
        if (requestError.name !== 'AbortError') setFailed(true)
      }
    }

    load()
    const timer = setInterval(load, REFRESH_MS)
    return () => {
      controller.abort()
      clearInterval(timer)
    }
  }, [])

  // 가격이 바뀌면 잠깐 깜빡이는 효과
  useEffect(() => {
    tickerStocks.forEach((stock) => {
      const symbol = stock.symbol
      const nextPrice = Number(prices[symbol]?.price)
      if (!Number.isFinite(nextPrice) || nextPrice === 0) return

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
  }, [prices])

  useEffect(() => () => {
    Object.values(flashTimersRef.current).forEach(clearTimeout)
  }, [])

  const renderTickerGroup = (copyIndex) => (
    <div
      className='ticker-group'
      aria-hidden={copyIndex === 1 ? 'true' : undefined}
    >
      {tickerStocks.map((stock) => {
        const quote = prices[stock.symbol]
        const hasQuote = quote && !quote.error && Number(quote.price) > 0
        const change = hasQuote ? Number(quote.change) : 0
        const isSaved = hydrated && watchlist.includes(stock.symbol)
        const flashDirection = priceFlashes[stock.symbol]
        const flashClass = flashDirection === 'up'
          ? 'animate-[ticker-flash-up_650ms_ease-out]'
          : flashDirection === 'down'
            ? 'animate-[ticker-flash-down_650ms_ease-out]'
            : ''

        return (
          <button
            key={`${copyIndex}-${stock.symbol}`}
            type='button'
            tabIndex={copyIndex === 1 ? -1 : 0}
            className={`ticker-item ${activeSymbol === stock.symbol ? 'active' : ''} ${flashClass}`}
            onClick={() => onSelectStock(stock)}
          >
            {isSaved && <FaStar className='ticker-star' aria-label='관심 종목' />}
            <span>{stock.name}</span>
            <strong>{hasQuote ? won(quote.price) : '—'}</strong>
            <small className={change >= 0 ? 'positive' : 'negative'}>
              {hasQuote ? `${change >= 0 ? '+' : ''}${change.toFixed(2)}%` : failed ? '연결 지연' : '…'}
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