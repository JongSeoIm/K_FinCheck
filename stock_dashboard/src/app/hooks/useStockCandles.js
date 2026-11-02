'use client'

import { useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

export default function useStockCandles(symbol, period = 'D', days = 40, enabled = true) {
  const [data, setData] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (!symbol || !enabled) return

    const controller = new AbortController()

    const fetchCandles = async () => {
      try {
        setLoading(true)
        setError(null)
        const response = await fetch(
          `${BACKEND_URL}/api/stocks/${symbol}/candles?period=${period}&days=${days}`,
          { signal: controller.signal }
        )
        const result = await response.json()
        if (!response.ok) {
          throw new Error(result.error || '시세 데이터 조회 실패')
        }
        setData(Array.isArray(result) ? result : [])
      } catch (err) {
        if (err.name !== 'AbortError') {
          setError(err.message)
        }
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false)
        }
      }
    }

    fetchCandles()
    return () => controller.abort()
  }, [symbol, period, days, enabled])

  return { data, loading, error }
}
