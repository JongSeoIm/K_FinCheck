'use client'

import { useRouter } from 'next/navigation'
import { useEffect, useState } from 'react'
import { FaRegStar, FaStar } from 'react-icons/fa'
import useStockStore from '@/app/store/useStockStore'

const won = (value) => (value == null || Number.isNaN(Number(value)) ? '—' : '₩' + Math.round(Number(value)).toLocaleString('ko-KR'))

function formatMarketCap(value) {
  if (!value) return '—'
  const trillion = value / 1e12
  if (trillion >= 1) return `${trillion.toLocaleString('ko-KR', { maximumFractionDigits: 1 })}조원`
  return `${Math.round(value / 1e8).toLocaleString('ko-KR')}억원`
}

const shares = (value) => (value == null || Number.isNaN(Number(value)) ? '—' : Math.round(Number(value)).toLocaleString('ko-KR') + '주')

const time = (iso) => (iso ? new Date(iso).toLocaleTimeString('ko-KR') : '—')

// 선택 종목 시세. 값은 WebSocket/폴링으로 받은 store 의 실제 시세만 씁니다 (예시 값 없음)
export default function StockInfo({ stock }) {
  const router = useRouter()
  const quote = useStockStore((state) => state.quotes[stock.code])
  const connectionState = useStockStore((state) => state.connection.state)
  const user = useStockStore((state) => state.user)
  const accountStatus = useStockStore((state) => state.accountStatus)
  const watchlist = useStockStore((state) => state.watchlist)
  const addToWatchlist = useStockStore((state) => state.addToWatchlist)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)
  const [marketCap, setMarketCap] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    fetch(`/api/kis/profile/${stock.code}`, { signal: controller.signal })
      .then((response) => (response.ok ? response.json() : null))
      .then((result) => setMarketCap(result?.marketCapitalization ?? null))
      .catch(() => { })
    return () => controller.abort()
  }, [stock.code])

  const hasQuote = quote && Number(quote.price) > 0
  const offline = connectionState === 'disconnected' || connectionState === 'not-configured'
  const failed = !hasQuote && offline
  const change = hasQuote ? Number(quote.change) : null
  const priceChange = hasQuote ? Number(quote.priceChange) : null
  const tone = change > 0 ? 'positive' : change < 0 ? 'negative' : ''
  const sign = change > 0 ? '+' : change < 0 ? '−' : ''
  const isSaved = watchlist.includes(stock.code)

  const handleToggle = async () => {
    const loginPage = `/login?symbol=${stock.code}`
    if (!user) {
      router.push(loginPage)
      return
    }
    setSaving(true)
    setSaveError('')
    try {
      await (isSaved ? removeFromWatchlist(stock.code) : addToWatchlist(stock.code))
    } catch (error) {
      if (error.code === 'LOGIN_REQUIRED') router.push(loginPage)
      else setSaveError(error.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className='panel quote-overview'>
      <div className='quote-heading'>
        <div>
          <h2>{stock.name || stock.code}</h2>
          <p>{stock.code} · {stock.market || 'KRX'}{stock.industry ? ` · ${stock.industry}` : ''}</p>
        </div>
        <div className='watch-toggle-box'>
          <button
            className={`watch-toggle ${isSaved ? 'saved' : ''}`}
            type='button'
            onClick={handleToggle}
            disabled={accountStatus === 'loading' || saving}
          >
            {isSaved ? <FaStar aria-hidden='true' /> : <FaRegStar aria-hidden='true' />}
            {isSaved ? '관심 종목 해제' : '관심 종목 추가'}
          </button>
          {saveError && <small className='inline-warning' role='alert'>{saveError}</small>}
        </div>
      </div>

      <div className='quote-body'>
        <div className='headline-price'>
          <span>현재가</span>
          {hasQuote ? (
            <strong className={tone}>{won(quote.price)}</strong>
          ) : failed ? (
            <strong>—</strong>
          ) : (
            <span className='price-skeleton' />
          )}
          <p className={tone}>
            {hasQuote
              ? `${sign}${Math.abs(priceChange).toLocaleString('ko-KR')}원 (${sign}${Math.abs(change).toFixed(2)}%)`
              : '전일 대비 —'}
          </p>
          <div className='update-time'>
            마지막 갱신 {time(quote?.updatedAt)}
            {quote?.tradeTime ? ` · 체결 ${quote.tradeTime}` : ''}
            {quote?.source === 'realtime' ? ' · 실시간' : quote?.source === 'rest' ? ' · 주기 조회' : ''}
          </div>
          {offline && (
            <small className='inline-warning' role='alert'>
              {hasQuote ? '연결이 끊겨 마지막으로 받은 값을 표시 중입니다.' : '시세를 받을 수 없습니다. 상단 연결 상태를 확인해 주세요.'}
            </small>
          )}
        </div>

        <dl className='quote-metrics'>
          <div><dt>시가</dt><dd>{hasQuote ? won(quote.open) : '—'}</dd></div>
          <div><dt>고가</dt><dd>{hasQuote ? won(quote.high) : '—'}</dd></div>
          <div><dt>저가</dt><dd>{hasQuote ? won(quote.low) : '—'}</dd></div>
          <div><dt>전일 종가</dt><dd>{hasQuote ? won(quote.prevClose) : '—'}</dd></div>
          <div><dt>누적거래량</dt><dd>{hasQuote ? shares(quote.volume) : '—'}</dd></div>
          <div><dt>시가총액</dt><dd>{formatMarketCap(quote?.marketCapitalization || marketCap)}</dd></div>
        </dl>
      </div>
    </section>
  )
}
