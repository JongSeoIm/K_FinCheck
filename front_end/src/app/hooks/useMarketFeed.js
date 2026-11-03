'use client'

import { useEffect } from 'react'
import useStockStore from '@/app/store/useStockStore'

const POLL_MS = 10000
const HEARTBEAT_TIMEOUT_MS = 40000 // 서버가 15초마다 status 를 보내므로 40초 동안 아무것도 없으면 끊긴 것으로 봅니다
const RECONNECT_MAX_MS = 30000

function socketUrl() {
  if (process.env.NEXT_PUBLIC_WS_URL) return process.env.NEXT_PUBLIC_WS_URL
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${window.location.hostname}:8080/ws/stock`
}

// 지원 종목 목록을 받고, 시세를 WebSocket(서버 push)으로 받습니다.
// WebSocket 이 끊기면 다시 연결을 시도하면서 그동안 REST 를 10초마다 polling 합니다.
export default function useMarketFeed() {
  const setStocks = useStockStore((state) => state.setStocks)
  const setQuote = useStockStore((state) => state.setQuote)
  const setConnection = useStockStore((state) => state.setConnection)

  useEffect(() => {
    let closed = false
    let socket = null
    let codes = []
    let reconnectTimer = null
    let pollTimer = null
    let heartbeatTimer = null
    let stocksTimer = null
    let reconnectDelay = 2000

    const applyQuotes = (map) => {
      Object.entries(map).forEach(([code, quote]) => {
        if (quote && !quote.error && Number(quote.price) > 0) setQuote(code, quote)
      })
    }

    // REST 로 한 번에 받기 (처음 화면 · WebSocket 끊김 동안)
    const poll = async () => {
      if (codes.length === 0) return false
      try {
        const response = await fetch(`/api/kis/prices?codes=${codes.join(',')}`, { cache: 'no-store' })
        const result = await response.json()
        if (!response.ok) throw new Error(result.error)
        const values = Object.values(result)
        if (values.length > 0 && values.every((q) => q?.code === 'KIS_NOT_CONFIGURED')) {
          setConnection({ state: 'not-configured' })
          return false
        }
        applyQuotes(result)
        return true
      } catch {
        return false
      }
    }

    const startPolling = () => {
      if (pollTimer) return
      const run = async () => {
        const ok = await poll()
        if (closed || socket?.readyState === WebSocket.OPEN) return
        const current = useStockStore.getState().connection.state
        if (current !== 'not-configured' || ok) setConnection({ state: ok ? 'polling' : 'disconnected' })
      }
      run()
      pollTimer = setInterval(run, POLL_MS)
    }

    const stopPolling = () => {
      clearInterval(pollTimer)
      pollTimer = null
    }

    const resetHeartbeat = () => {
      clearTimeout(heartbeatTimer)
      heartbeatTimer = setTimeout(() => socket?.close(), HEARTBEAT_TIMEOUT_MS)
    }

    const connect = () => {
      if (closed) return
      try {
        socket = new WebSocket(socketUrl())
      } catch {
        scheduleReconnect()
        return
      }

      socket.onopen = () => {
        reconnectDelay = 2000
        stopPolling()
        resetHeartbeat()
      }

      socket.onmessage = (event) => {
        resetHeartbeat()
        let message
        try {
          message = JSON.parse(event.data)
        } catch {
          return
        }
        if (message.type === 'quote' && message.data?.code) {
          setQuote(message.data.code, message.data)
        } else if (message.type === 'status') {
          setConnection({
            state: !message.kisConfigured ? 'not-configured' : message.realtime ? 'live' : 'server-polling',
            marketOpen: message.marketOpen,
          })
        }
      }

      socket.onclose = () => {
        clearTimeout(heartbeatTimer)
        if (closed) return
        startPolling()
        scheduleReconnect()
      }

      socket.onerror = () => socket?.close()
    }

    const scheduleReconnect = () => {
      clearTimeout(reconnectTimer)
      reconnectTimer = setTimeout(connect, reconnectDelay)
      reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS)
    }

    // 지원 종목 목록 (DB). 실패하면 10초 뒤 다시 시도
    const loadStocks = async () => {
      try {
        const response = await fetch('/api/kis/stocks', { cache: 'no-store' })
        const result = await response.json()
        if (!response.ok || !Array.isArray(result)) throw new Error(result.error || '종목 목록을 불러오지 못했습니다.')
        if (closed) return
        setStocks(result)
        codes = result.map((stock) => stock.code)
        await poll() // 첫 화면은 REST 로 바로 채우고
        connect() // 이후는 WebSocket push
      } catch (error) {
        if (closed) return
        setStocks(useStockStore.getState().stocks, error.message)
        setConnection({ state: 'disconnected' })
        stocksTimer = setTimeout(loadStocks, POLL_MS)
      }
    }

    loadStocks()

    return () => {
      closed = true
      clearTimeout(reconnectTimer)
      clearTimeout(heartbeatTimer)
      clearTimeout(stocksTimer)
      stopPolling()
      socket?.close()
    }
  }, [setStocks, setQuote, setConnection])
}
