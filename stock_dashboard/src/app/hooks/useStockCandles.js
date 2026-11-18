'use client'

import { useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

export default function useStockCandles(symbol, period = 'D', days = 40, enabled = true) {
  const [result, setResult] = useState(null)
  const requestKey = `${symbol}:${period}:${days}`

  useEffect(() => {
    if (!symbol || !enabled) return

    const controller = new AbortController()

    const fetchCandles = async () => {
      try {
        const response = await fetch(
          `${BACKEND_URL}/api/stocks/${symbol}/candles?period=${period}&days=${days}`,
          { signal: controller.signal }
        )
        const result = await response.json()
        if (!response.ok) {
          throw new Error(result.error || '시세 데이터 조회 실패')
        }
        if (!controller.signal.aborted) setResult({ key: requestKey, data: Array.isArray(result) ? result : [], error: null })
      } catch (err) {
        if (!controller.signal.aborted) setResult({ key: requestKey, data: [],
          error: err instanceof TypeError ? '차트 서버에 연결하지 못했습니다.' : err.message })
      }
    }

    fetchCandles()
    return () => controller.abort()
  }, [symbol, period, days, enabled, requestKey])

  const current = result?.key === requestKey ? result : null
  return { data: current?.data || [], loading: enabled && !current, error: current?.error || null }
}
