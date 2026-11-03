'use client'

import { useEffect, useState } from 'react'
import useStockStore from '@/app/store/useStockStore'

// Spring(KIS) 현재가를 5초마다 다시 받아 옵니다.
export default function useStockData(symbol, intervalMs = 5000) {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const setPrice = useStockStore((state) => state.setPrice)

  useEffect(() => {
    if (!symbol) return undefined
    const controller = new AbortController()
    let timer

    const load = async () => {
      try {
        const response = await fetch(`/api/kis/price/${symbol}`, {
          signal: controller.signal,
          cache: 'no-store',
        })
        const result = await response.json()
        if (!response.ok || result.error) {
          throw new Error(result.error || '시세 조회에 실패했습니다.')
        }
        setData(result)
        // 차트 등 다른 화면이 같은 값을 쓰도록 공유 (한 곳에서만 시세를 받아 옵니다)
        setPrice(symbol, { price: result.price, at: Date.now() })
        setError(null)
      } catch (requestError) {
        if (requestError.name !== 'AbortError') setError(requestError.message)
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    }

    setLoading(true)
    load()
    timer = setInterval(load, intervalMs)

    return () => {
      controller.abort()
      clearInterval(timer)
    }
  }, [symbol, intervalMs, setPrice])

  return { data, loading, error }
}