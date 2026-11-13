'use client'

import { useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

export default function useStockData(symbol, enabled = true) {
  const [result, setResult] = useState(null)

  useEffect(() => {
    if (!symbol || !enabled) return

    const controller = new AbortController()

    const fetchStockData = async () => {
      try {
        const response = await fetch(`${BACKEND_URL}/api/stocks/${symbol}/price`, {
          signal: controller.signal,
        })
        const result = await response.json()
        if (!response.ok) {
          throw new Error(result.error || '시세 조회에 실패했습니다.')
        }
        if (!controller.signal.aborted) setResult({ symbol, data: { ...result, fetchedAt: new Date().toISOString() }, error: null })
      } catch (requestError) {
        if (!controller.signal.aborted) setResult({ symbol, data: null,
          error: requestError instanceof TypeError ? '시세 서버에 연결하지 못했습니다.' : requestError.message })
      }
    }

    fetchStockData()
    return () => controller.abort()
  }, [symbol, enabled])

  const current = result?.symbol === symbol ? result : null
  return { data: current?.data || null, loading: enabled && !current, error: current?.error || null }
}
