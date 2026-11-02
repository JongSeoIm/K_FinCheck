'use client'

import { useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

export default function useStockData(symbol) {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (!symbol) return

    const controller = new AbortController()

    const fetchStockData = async () => {
      try {
        setLoading(true)
        setError(null)
        const response = await fetch(`${BACKEND_URL}/api/stocks/${symbol}/price`, {
          signal: controller.signal,
        })
        const result = await response.json()
        if (!response.ok) {
          throw new Error(result.error || '시세 조회에 실패했습니다.')
        }
        setData(result)
      } catch (requestError) {
        if (requestError.name !== 'AbortError') {
          setError(requestError.message)
        }
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false)
        }
      }
    }

    fetchStockData()
    return () => controller.abort()
  }, [symbol])

  return { data, loading, error }
}
