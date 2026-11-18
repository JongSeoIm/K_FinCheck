'use client'

import { useEffect, useSyncExternalStore } from 'react'

const KEY = 'kfc-recent-stock'
const EVENT = 'kfc-recent-stock-change'
const MAX_RECENT = 5

function read() {
  try { return window.localStorage.getItem(KEY) } catch { return null }
}

function parseRecent(value) {
  if (typeof value !== 'string') return []
  if (/^\d{6}$/.test(value)) return [value]
  try {
    const recent = JSON.parse(value)
    return Array.isArray(recent) ? recent.filter(symbol => typeof symbol === 'string' && /^\d{6}$/.test(symbol)) : []
  } catch { return [] }
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
  const snapshot = useSyncExternalStore(subscribe, read, () => null)
  const recent = parseRecent(snapshot)
  useEffect(() => {
    if (!symbol || !/^\d{6}$/.test(symbol)) return
    try {
      const previous = parseRecent(read())
      if (previous[0] === symbol) return
      window.localStorage.setItem(KEY, JSON.stringify([symbol, ...previous.filter(item => item !== symbol)].slice(0, MAX_RECENT)))
      window.dispatchEvent(new Event(EVENT))
    } catch { /* 저장이 차단된 환경에서도 종목 조회는 유지한다. */ }
  }, [symbol])
  return [...new Set([symbol, ...recent].filter(Boolean))].slice(0, MAX_RECENT)
}
