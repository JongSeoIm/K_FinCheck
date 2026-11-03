'use client'

import { useState } from 'react'
import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')

// 관심 종목: 6자리 코드로 추가(서버에서 지원 종목인지 확인)·삭제, localStorage 저장, 클릭하면 해당 종목으로 이동
export default function WatchlistPanel({ activeCode, onSelectStock }) {
  const hydrated = useHydrated()
  const watchlist = useStockStore((state) => state.watchlist)
  const stocks = useStockStore((state) => state.stocks)
  const quotes = useStockStore((state) => state.quotes)
  const connectionState = useStockStore((state) => state.connection.state)
  const addToWatchlist = useStockStore((state) => state.addToWatchlist)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)

  const [code, setCode] = useState('')
  const [message, setMessage] = useState('')
  const [checking, setChecking] = useState(false)

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
    setChecking(true)
    try {
      const response = await fetch(`/api/kis/stocks/${value}`, { cache: 'no-store' })
      const result = await response.json().catch(() => ({}))
      if (response.status === 404) {
        setMessage(`지원하지 않는 종목입니다. (지원: ${stocks.map((s) => `${s.code} ${s.name}`).join(', ') || '—'})`)
        return
      }
      if (!response.ok) throw new Error(result.error || '확인에 실패했습니다.')
      addToWatchlist(value)
      setCode('')
      setMessage('')
    } catch {
      setMessage('서버에 연결할 수 없어 종목을 확인하지 못했습니다.')
    } finally {
      setChecking(false)
    }
  }

  return (
    <section className='panel watchlist-panel'>
      <div className='panel-heading'>
        <h2>관심 종목</h2>
        <span className='count-badge'>{hydrated ? watchlist.length : 0}</span>
      </div>

      <form className='watchlist-form' onSubmit={handleAdd}>
        <input
          type='text'
          inputMode='numeric'
          maxLength={6}
          value={code}
          onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
          placeholder='종목코드 6자리'
          aria-label='관심 종목 코드 입력'
        />
        <button type='submit' disabled={checking}>{checking ? '확인 중' : '추가'}</button>
      </form>
      {message && <small className='form-message' role='alert'>{message}</small>}

      {!hydrated ? (
        <div className='skeleton-list'>
          {[1, 2].map((item) => <span key={item} className='skeleton-row compact' />)}
        </div>
      ) : watchlist.length === 0 ? (
        <div className='empty-state'>
          <strong>아직 저장한 종목이 없습니다.</strong>
          <p>종목코드를 입력하거나, 종목 정보의 ‘관심 종목 추가’를 눌러 보세요.</p>
        </div>
      ) : (
        <ul className='watchlist'>
          {watchlist.map((symbol) => {
            const stock = stocks.find((item) => item.code === symbol)
            const quote = quotes[symbol]
            const hasQuote = quote && Number(quote.price) > 0
            const change = hasQuote ? Number(quote.change) : 0
            const offline = connectionState === 'disconnected' || connectionState === 'not-configured'

            return (
              <li key={symbol} className={activeCode === symbol ? 'active' : ''}>
                <button className='watchlist-select' type='button' onClick={() => onSelectStock(symbol)} disabled={stocks.length > 0 && !stock}>
                  <span>
                    <strong>{symbol}</strong>
                    <small>{stock?.name || (stocks.length > 0 ? '지원 종료된 종목' : '')}</small>
                  </span>
                  <span className='watch-price'>
                    {hasQuote ? (
                      <>
                        {won(quote.price)}{' '}
                        <small className={change > 0 ? 'positive' : change < 0 ? 'negative' : ''}>
                          {change > 0 ? '+' : ''}{change.toFixed(2)}%
                        </small>
                      </>
                    ) : offline ? '연결 끊김' : stocks.length > 0 && !stock ? '—' : '조회 중…'}
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
