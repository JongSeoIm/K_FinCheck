'use client'

import { useEffect, useRef, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

export default function useStockListQuotes(symbols, trades) {
  const key = symbols.join(',')
  const live = useRef(trades)
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState({ data: {}, errors: {}, loading: true })
  useEffect(() => { live.current = trades }, [trades])

  useEffect(() => {
    const controller = new AbortController()
    let timer
    async function load() {
      if (controller.signal.aborted) return
      setResult(current => ({ ...current, loading: true }))
      for (const symbol of key.split(',').filter(Boolean)) {
        if (controller.signal.aborted) return
        if (live.current[symbol]) continue
        try {
          const response = await fetch(`${BACKEND_URL}/api/stocks/${symbol}/price`, { signal: controller.signal })
          const quote = await response.json()
          if (!response.ok) throw new Error(quote.error || '시세 조회 실패')
          if (controller.signal.aborted) return
          setResult(current => {
            const errors = { ...current.errors }
            delete errors[symbol]
            return { ...current, data: { ...current.data, [symbol]: { ...quote, fetchedAt: new Date().toISOString() } }, errors }
          })
        } catch (error) {
          if (controller.signal.aborted) return
          setResult(current => ({ ...current, errors: { ...current.errors, [symbol]: '시세 자료를 확인할 수 없습니다.' } }))
        }
      }
      if (!controller.signal.aborted) {
        setResult(current => ({ ...current, loading: false }))
        timer = setTimeout(load, 60000)
      }
    }
    load()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [key, attempt])

  return { ...result, retry: () => setAttempt(value => value + 1) }
}
