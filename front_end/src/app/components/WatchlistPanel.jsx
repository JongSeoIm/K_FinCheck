'use client'

import { useState } from 'react'
import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'
import { marketStocks } from '@/app/lib/market'

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')

export default function WatchlistPanel({ activeSymbol, onSelectStock }) {
  const hydrated = useHydrated()
  const watchlist = useStockStore((state) => state.watchlist)
  const user = useStockStore((state) => state.user)
  const authReady = useStockStore((state) => state.authReady)
  const addToWatchlist = useStockStore((state) => state.addToWatchlist)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)

  // 시세는 서버가 보낸 최신 값(Polling/Push 공통)을 그대로 씁니다.
  const quotes = useStockStore((state) => state.quotes)
  const failed = useStockStore((state) => state.feedStatus === 'disconnected')
  const [code, setCode] = useState('')
  const [message, setMessage] = useState('')

  // 종목코드(6자리)로 직접 추가
  const handleAdd = async (event) => {
    event.preventDefault()
    const value = code.trim()
    if (!/^\d{6}$/.test(value)) {
      setMessage('6자리 종목코드를 입력해 주세요. (예: 005930)')
      return
    }
    if (watchlist.includes(value)) {
      setMessage('이미 관심 종목에 있습니다.')
      return
    }
    // 서버(계정별 DB)에 저장합니다. 로그인 전이면 로그인 화면으로 안내됩니다.
    const result = await addToWatchlist(value)
    if (!result.ok) {
      setMessage(result.error)
      return
    }
    setCode('')
    setMessage('')
  }

  return (
    <section className='panel watchlist-panel'>
      <div className='panel-heading'>
        <h2>관심 종목</h2>
        <span className='count-badge'>{hydrated ? watchlist.length : 0}</span>
      </div>

      <form onSubmit={handleAdd} style={{ display: 'flex', gap: '6px', padding: '8px 12px 0' }}>
        <input
          type='text'
          inputMode='numeric'
          maxLength={6}
          value={code}
          onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
          placeholder='종목코드 6자리'
          aria-label='관심 종목 코드 입력'
          style={{ flex: 1, minWidth: 0, padding: '6px 8px', border: '1px solid #ccc', fontSize: '0.85rem' }}
        />
        <button type='submit' style={{ padding: '6px 12px', fontSize: '0.85rem' }}>추가</button>
      </form>
      {message && (
        <small style={{ display: 'block', padding: '4px 12px 0', color: '#b00020' }}>{message}</small>
      )}

      {!hydrated ? (
        <div className='skeleton-list'>
          {[1, 2].map((item) => <span key={item} className='skeleton-row compact' />)}
        </div>
      ) : watchlist.length === 0 ? (
        <div className='empty-state'>
          {authReady && !user ? (
            <>
              <strong>로그인하면 관심 종목을 저장할 수 있습니다.</strong>
              <p>상단의 ‘로그인’을 누르면 내 계정의 관심 종목이 표시됩니다.</p>
            </>
          ) : (
            <>
              <strong>아직 저장한 종목이 없습니다.</strong>
              <p>위에서 종목코드를 입력하거나, 종목 상단의 ‘관심 종목 추가’를 눌러 보세요.</p>
            </>
          )}
        </div>
      ) : (
        <ul className='watchlist'>
          {watchlist.map((symbol) => {
            const stock = marketStocks.find((item) => item.symbol === symbol) || {
              symbol,
              name: symbol,
            }
            const quote = quotes[symbol]
            const hasQuote = quote && !quote.error && Number(quote.price) > 0
            const change = hasQuote ? Number(quote.change) : 0

            return (
              <li key={symbol} className={activeSymbol === symbol ? 'active' : ''}>
                <button className='watchlist-select' type='button' onClick={() => onSelectStock(stock)}>
                  <span>
                    <strong>{symbol}</strong>
                    <small>{stock.name}</small>
                  </span>
                  <span className='watch-price'>
                    {hasQuote ? (
                      <>
                        {won(quote.price)}{' '}
                        <small className={change >= 0 ? 'positive' : 'negative'}>
                          {change >= 0 ? '+' : ''}{change.toFixed(2)}%
                        </small>
                      </>
                    ) : quote?.error ? '조회 불가' : failed ? '연결 지연' : '조회 중…'}
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