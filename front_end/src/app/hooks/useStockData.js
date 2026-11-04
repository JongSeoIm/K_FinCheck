'use client'

import { useEffect, useState } from 'react'
import useStockStore from '@/app/store/useStockStore'

// 선택 종목의 현재가. 서버가 Polling/Push 로 보낸 최신 시세(store.quotes)를 그대로 씁니다.
// 첫 시세가 도착하기 전에만 /api/kis/price 를 한 번 조회합니다.
export default function useStockData(symbol) {
  const quote = useStockStore((state) => state.quotes[symbol])
  const feedStatus = useStockStore((state) => state.feedStatus)
  const setPrice = useStockStore((state) => state.setPrice)
  const [fallback, setFallback] = useState(null)
  const [error, setError] = useState(null)
  const hasQuote = Boolean(quote)

  useEffect(() => {
    if (!symbol || hasQuote) return undefined
    const controller = new AbortController()
    fetch(`/api/kis/price/${symbol}`, { signal: controller.signal, cache: 'no-store' })
      .then((response) => response.json().then((body) => ({ ok: response.ok, body })))
      .then(({ ok, body }) => {
        if (!ok || body.error) throw new Error(body.error || '시세 조회에 실패했습니다.')
        setFallback(body)
        setError(null)
      })
      .catch((requestError) => {
        if (requestError.name !== 'AbortError') setError(requestError.message)
      })
    return () => controller.abort()
  }, [symbol, hasQuote])

  // 차트 등 다른 화면이 같은 값을 쓰도록 공유
  useEffect(() => {
    if (!quote) return
    setPrice(symbol, { price: quote.price, prevClose: quote.prevClose, at: Date.now() })
  }, [quote, symbol, setPrice])

  const data = quote ?? fallback
  const feedError = feedStatus === 'disconnected' ? '실시간 연결 끊김' : null
  return { data, loading: !data && !error, error: data ? feedError : error }
}
