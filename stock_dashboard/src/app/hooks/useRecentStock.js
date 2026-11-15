'use client'

import { useEffect, useSyncExternalStore } from 'react'

const KEY = 'kfc-recent-stock'
const EVENT = 'kfc-recent-stock-change'

function read() {
  try { return window.localStorage.getItem(KEY) } catch { return null }
}

function subscribe(notify) {
  const changed = event => { if (event.type === EVENT || event.key === KEY || event.key === null) notify() }
  window.addEventListener('storage', changed)
  window.addEventListener(EVENT, changed)
  return () => {
    window.removeEventListener('storage', changed)
    window.removeEventListener(EVENT, changed)
  }
}

export default function useRecentStock(symbol) {
  const recent = useSyncExternalStore(subscribe, read, () => null)
  useEffect(() => {
    if (!symbol || !/^\d{6}$/.test(symbol)) return
    try {
      if (read() === symbol) return
      window.localStorage.setItem(KEY, symbol)
      window.dispatchEvent(new Event(EVENT))
    } catch { /* 저장이 차단된 환경에서도 종목 조회는 유지한다. */ }
  }, [symbol])
  return symbol || recent
}
