'use client'

import { useEffect, useRef, useState } from 'react'
import { FaStar } from 'react-icons/fa'
import useHydrated from '../hooks/useHydrated'
import useStockStore from '../store/useStockStore'
import { signedPercent, won } from '@/app/lib/format'


// 상단 티커: 서버 DB 의 지원 종목 전체를 실제 시세로 표시 (WebSocket 으로 갱신, 가격이 바뀌면 깜빡임)
export default function MarketTicker({ activeCode, onSelectStock }) {
  const hydrated = useHydrated()
  const watchlist = useStockStore((state) => state.watchlist)
  const stocks = useStockStore((state) => state.stocks)
  const quotes = useStockStore((state) => state.quotes)
  const connectionState = useStockStore((state) => state.connection.state)
  const [priceFlashes, setPriceFlashes] = useState({})
  const previousPricesRef = useRef({})
  const flashTimersRef = useRef({})

  useEffect(() => {
    stocks.forEach(({ code }) => {
      const nextPrice = Number(quotes[code]?.price)
      if (!Number.isFinite(nextPrice) || nextPrice === 0) return

      const previousPrice = previousPricesRef.current[code]
      previousPricesRef.current[code] = nextPrice
      if (previousPrice === undefined || previousPrice === nextPrice) return

      const direction = nextPrice > previousPrice ? 'up' : 'down'
      setPriceFlashes((current) => ({ ...current, [code]: direction }))
      clearTimeout(flashTimersRef.current[code])
      flashTimersRef.current[code] = setTimeout(() => {
        setPriceFlashes((current) => {
          const next = { ...current }
          delete next[code]
          return next
        })
      }, 650)
    })
  }, [quotes, stocks])

  useEffect(() => () => {
    Object.values(flashTimersRef.current).forEach(clearTimeout)
  }, [])

  // 종목이 적으면 흐르지 않게 고정 표시
  const scrolling = stocks.length > 4
  const disconnected = connectionState === 'disconnected' || connectionState === 'not-configured'

  const renderTickerGroup = (copyIndex) => (
    <div className='ticker-group' aria-hidden={copyIndex === 1 ? 'true' : undefined}>
      {stocks.map((stock) => {
        const quote = quotes[stock.code]
        const hasQuote = quote && Number(quote.price) > 0
        const change = hasQuote ? Number(quote.change) : 0
        const isSaved = hydrated && watchlist.includes(stock.code)
        const flashDirection = priceFlashes[stock.code]
        const flashClass = flashDirection === 'up'
          ? 'animate-[ticker-flash-up_650ms_ease-out]'
          : flashDirection === 'down'
            ? 'animate-[ticker-flash-down_650ms_ease-out]'
            : ''

        return (
          <button
            key={`${copyIndex}-${stock.code}`}
            type='button'
            tabIndex={copyIndex === 1 ? -1 : 0}
            className={`ticker-item ${activeCode === stock.code ? 'active' : ''} ${flashClass}`}
            onClick={() => onSelectStock(stock.code)}
          >
            {isSaved && <FaStar className='ticker-star' aria-label='관심 종목' />}
            <span>{stock.name}</span>
            <strong>{hasQuote ? won(quote.price) : '—'}</strong>
            <small className={!hasQuote ? '' : change > 0 ? 'positive' : change < 0 ? 'negative' : ''}>
              {hasQuote ? signedPercent(change) : disconnected ? '연결 끊김' : '조회 중…'}
            </small>
          </button>
        )
      })}
    </div>
  )

  return (
    <section className='ticker-bar' aria-label='지원 종목 시세'>
      {stocks.length === 0 ? (
        <p className='ticker-empty'>{disconnected ? '서버에 연결되지 않아 시세를 표시할 수 없습니다.' : '종목 목록을 불러오는 중…'}</p>
      ) : (
        <div className={`ticker-track ${scrolling ? 'animate-[ticker-scroll_36s_linear_infinite]' : ''}`}>
          {renderTickerGroup(0)}
          {scrolling && renderTickerGroup(1)}
        </div>
      )}
    </section>
  )
}
