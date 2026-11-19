'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

export default function useIntradayCandles(symbol, enabled) {
  const [result, setResult] = useState(null)
  const active = useRef(null)
  useEffect(() => {
    if (!enabled || !symbol) return
    const session = { symbol, rows: new Map(), nextBefore: null, hasMore: true, busy: false, disposed: false, controllers: new Set() }
    active.current = session
    const publish = (error = null) => {
      if (session.disposed) return
      setResult({ symbol, data: [...session.rows.values()].sort((a, b) => Date.parse(a.date) - Date.parse(b.date)),
        hasMore: session.hasMore, loading: session.busy, error })
    }
    session.fetch = async (older = false) => {
      if (session.busy || session.disposed || (older && !session.hasMore)) return
      session.busy = true
      publish()
      const controller = new AbortController()
      session.controllers.add(controller)
      const before = older ? session.nextBefore : null
      try {
        const response = await fetch(`${BACKEND_URL}/api/stocks/${symbol}/intraday${before ? `?before=${encodeURIComponent(before)}` : ''}`, { signal: controller.signal, cache: 'no-store' })
        const page = await response.json()
        if (!response.ok) throw new Error(page.error || '과거 분봉 조회 실패')
        if (!Array.isArray(page.candles)) throw new Error('분봉 응답 형식을 확인해 주세요.')
        if (session.disposed) return
        const wasEmpty = session.rows.size === 0
        for (const candle of page.candles) session.rows.set(candle.date, candle)
        if (older || wasEmpty) {
          session.nextBefore = page.nextBefore
          session.hasMore = Boolean(page.hasMore && page.nextBefore && page.nextBefore !== before)
        }
        session.busy = false
        publish()
      } catch (error) {
        if (!session.disposed && error.name !== 'AbortError') {
          session.busy = false
          publish(error instanceof TypeError ? '분봉 서버에 연결하지 못했습니다.' : error.message)
        }
      } finally { session.controllers.delete(controller) }
    }
    session.fetch()
    const timer = setInterval(() => session.fetch(), 60000)
    return () => {
      session.disposed = true
      for (const controller of session.controllers) controller.abort()
      clearInterval(timer)
      if (active.current === session) active.current = null
    }
  }, [symbol, enabled])
  const loadMore = useCallback(() => active.current?.fetch(true), [])
  const retry = useCallback(() => active.current?.fetch(), [])
  const current = enabled && result?.symbol === symbol ? result : null
  return { data: current?.data || [], loading: enabled && (!current || current.loading), error: current?.error,
    hasMore: current?.hasMore ?? true, loadMore, retry }
}
