'use client'

import { useEffect, useState } from 'react'
import { FaRegStar, FaStar } from 'react-icons/fa'
import useHydrated from '@/app/hooks/useHydrated'
import useStockData from '@/app/hooks/useStockData'
import useStockStore from '@/app/store/useStockStore'

// 콤마 및 원화(₩) 포맷팅 함수
function formatCurrency(value, digits = 0) {
  const num = Number(value ?? 0)
  return '₩' + num.toLocaleString('ko-KR', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

// 시가총액 조 단위 포맷팅 함수
function formatMarketCap(value) {
  if (!value) return '—'
  const capInTrillion = value / 1000000000000
  if (capInTrillion >= 1) {
    return `${capInTrillion.toLocaleString('ko-KR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })}조원`
  }
  const capInBillion = value / 100000000
  return `${capInBillion.toLocaleString()}억원`
}

export default function StockInfo({ stock }) {
  const hydrated = useHydrated()
  const { data, loading, error } = useStockData(stock.symbol)
  const [profile, setProfile] = useState(null)
  const [lastUpdated, setLastUpdated] = useState('')

  const watchlist = useStockStore((state) => state.watchlist)
  const addToWatchlist = useStockStore((state) => state.addToWatchlist)
  const removeFromWatchlist = useStockStore((state) => state.removeFromWatchlist)

  useEffect(() => {
    const controller = new AbortController()
    fetch(`/api/kis/profile/${stock.symbol}`, { signal: controller.signal })
      .then((response) => response.ok ? response.json() : null)
      .then((result) => result && setProfile(result))
      .catch((requestError) => {
        if (requestError.name !== 'AbortError') console.error(requestError)
      })
    return () => controller.abort()
  }, [stock.symbol])

  useEffect(() => {
    if (!data?.updatedAt) return
    setLastUpdated(new Date(data.updatedAt).toLocaleTimeString('ko-KR'))
  }, [data])

  const isSaved = hydrated && watchlist.includes(stock.symbol)
  const displayPrice = data?.price ?? stock.price ?? 0
  const displayChange = data?.change ?? stock.change ?? 0
  const priceChange = data?.priceChange ?? 0
  const isRising = displayChange >= 0

  return (
    <section className='panel quote-overview'>
      <div className='quote-heading'>
        <div>
          <h2>{profile?.name || stock.name || stock.symbol}</h2>
          <p>{stock.symbol} · {profile?.exchange || 'KRX'} · {profile?.industry || '전기·전자'}</p>
        </div>
        <button
          className={`watch-toggle ${isSaved ? 'saved' : ''}`}
          type='button'
          onClick={() => isSaved ? removeFromWatchlist(stock.symbol) : addToWatchlist(stock.symbol)}
          disabled={!hydrated}
        >
          {isSaved ? <FaStar aria-hidden='true' /> : <FaRegStar aria-hidden='true' />}
          {isSaved ? '관심 종목 해제' : '관심 종목 추가'}
        </button>
      </div>

      <div className='quote-body'>
        <div className='headline-price'>
          <span>현재가</span>
          {loading ? <span className='price-skeleton' /> : <strong>{formatCurrency(displayPrice)}</strong>}
          <p className={isRising ? 'positive' : 'negative'}>
            {isRising ? '+' : ''}{formatCurrency(Math.abs(priceChange)).replace('₩', '')}원 ({isRising ? '+' : ''}{Number(displayChange).toFixed(2)}%)
          </p>
          <div className='update-time' style={{ fontSize: '0.75rem', color: '#888', marginTop: '4px' }}>마지막 갱신 {lastUpdated || '—'}</div>
          {error && <small className='inline-warning'>실시간 조회 지연</small>}
        </div>

        <dl className='quote-metrics' style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '12px', width: '100%', margin: 0 }}>
          <div>
            <dt style={{ fontSize: '0.85rem', color: '#666' }}>시가</dt>
            <dd style={{ fontSize: '1.1rem', fontWeight: 'bold', margin: '4px 0 0 0' }}>{data?.open == null ? '—' : formatCurrency(data.open)}</dd>
          </div>
          <div>
            <dt style={{ fontSize: '0.85rem', color: '#666' }}>고가</dt>
            <dd style={{ fontSize: '1.1rem', fontWeight: 'bold', margin: '4px 0 0 0' }}>{data?.high == null ? '—' : formatCurrency(data.high)}</dd>
          </div>
          <div>
            <dt style={{ fontSize: '0.85rem', color: '#666' }}>저가</dt>
            <dd style={{ fontSize: '1.1rem', fontWeight: 'bold', margin: '4px 0 0 0' }}>{data?.low == null ? '—' : formatCurrency(data.low)}</dd>
          </div>
          <div>
            <dt style={{ fontSize: '0.85rem', color: '#666' }}>전일 종가</dt>
            <dd style={{ fontSize: '1.1rem', fontWeight: 'bold', margin: '4px 0 0 0' }}>{(data?.prevClose ?? data?.previousClose) == null ? '—' : formatCurrency(data?.prevClose ?? data?.previousClose)}</dd>
          </div>
          <div>
            <dt style={{ fontSize: '0.85rem', color: '#666' }}>시가총액</dt>
            <dd style={{ fontSize: '1.1rem', fontWeight: 'bold', margin: '4px 0 0 0' }}>{formatMarketCap(profile?.marketCapitalization)}</dd>
          </div>
        </dl>
      </div>
    </section>
  )
}