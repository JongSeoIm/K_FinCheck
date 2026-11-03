'use client'

import { useEffect } from 'react'
import useStockStore from '@/app/store/useStockStore'

const POLL_MS = 10000 // Push 방식에서 WebSocket 이 끊겼을 때 임시로 조회하는 간격
const MANUAL_POLL_MS = 2000 // 사용자가 Polling 방식을 골랐을 때 조회 간격
const STATUS_MS = 15000 // Polling 방식에서 장 운영 여부·KIS 연결 상태를 다시 확인하는 간격
const HEARTBEAT_TIMEOUT_MS = 40000 // 서버가 15초마다 status 를 보내므로 40초 동안 아무것도 없으면 끊긴 것으로 봅니다
const RECONNECT_MAX_MS = 30000

function socketUrl() {
  if (process.env.NEXT_PUBLIC_WS_URL) return process.env.NEXT_PUBLIC_WS_URL
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${window.location.hostname}:8080/ws/stock`
}

// 지원 종목 목록을 받고 시세를 받아옵니다. 전달 방식은 사용자가 고릅니다 (feedMode).
// - push : WebSocket 으로 서버가 보내 주는 실시간 시세. 끊기면 다시 연결하면서 그동안 10초마다 조회합니다.
// - poll : WebSocket 을 쓰지 않고 2초마다 서버에 시세를 물어봅니다.
export default function useMarketFeed() {
  const feedMode = useStockStore((state) => state.feedMode)
  const setStocks = useStockStore((state) => state.setStocks)
  const setQuote = useStockStore((state) => state.setQuote)
  const setConnection = useStockStore((state) => state.setConnection)

  useEffect(() => {
    let closed = false
    let socket = null
    let codes = []
    let reconnectTimer = null
    let pollTimer = null
    let statusTimer = null
    let heartbeatTimer = null
    let stocksTimer = null
    let reconnectDelay = 2000

    const applyQuotes = (map) => {
      Object.entries(map).forEach(([code, quote]) => {
        if (quote && !quote.error && Number(quote.price) > 0) setQuote(code, quote)
      })
    }

    // REST 로 한 번에 받기 (첫 화면 · Polling 방식 · WebSocket 끊김 동안)
    // 결과: 'ok' | 'not-configured' | 'fail'
    const poll = async () => {
      if (codes.length === 0) return 'fail'
      try {
        const response = await fetch(`/api/kis/prices?codes=${codes.join(',')}`, { cache: 'no-store' })
        const result = await response.json()
        if (!response.ok) throw new Error(result.error)
        const values = Object.values(result)
        if (values.length > 0 && values.every((q) => q?.code === 'KIS_NOT_CONFIGURED')) return 'not-configured'
        applyQuotes(result)
        return 'ok'
      } catch {
        return 'fail'
      }
    }

    const stopPolling = () => {
      clearInterval(pollTimer)
      pollTimer = null
    }

    // ───── Polling 방식 (사용자 선택, 2초) ─────

    const loadStatus = async () => {
      try {
        const response = await fetch('/api/kis/status', { cache: 'no-store' })
        const status = await response.json()
        if (!closed && response.ok) setConnection({ marketOpen: status.marketOpen })
      } catch {
        // 시세 조회 결과로 연결 상태를 판단하므로 여기서는 무시
      }
    }

    const startManualPolling = () => {
      const run = async () => {
        const result = await poll()
        if (closed) return
        setConnection({ state: result === 'ok' ? 'manual-polling' : result === 'not-configured' ? 'not-configured' : 'disconnected' })
      }
      run()
      loadStatus()
      pollTimer = setInterval(run, MANUAL_POLL_MS)
      statusTimer = setInterval(loadStatus, STATUS_MS)
    }

    // ───── Push 방식 (WebSocket) ─────

    // WebSocket 이 끊긴 동안 임시로 10초마다 조회
    const startFallbackPolling = () => {
      if (pollTimer) return
      const run = async () => {
        const result = await poll()
        if (closed || socket?.readyState === WebSocket.OPEN) return
        if (result === 'not-configured') setConnection({ state: 'not-configured' })
        else setConnection({ state: result === 'ok' ? 'polling' : 'disconnected' })
      }
      run()
      pollTimer = setInterval(run, POLL_MS)
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
        startFallbackPolling()
        scheduleReconnect()
      }

      socket.onerror = () => socket?.close()
    }

    const scheduleReconnect = () => {
      clearTimeout(reconnectTimer)
      reconnectTimer = setTimeout(connect, reconnectDelay)
      reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS)
    }

    // ───── 시작 ─────

    // 지원 종목 목록 (DB). 실패하면 10초 뒤 다시 시도
    const loadStocks = async () => {
      try {
        const response = await fetch('/api/kis/stocks', { cache: 'no-store' })
        const result = await response.json()
        if (!response.ok || !Array.isArray(result)) throw new Error(result.error || '종목 목록을 불러오지 못했습니다.')
        if (closed) return
        setStocks(result)
        codes = result.map((stock) => stock.code)
        if (feedMode === 'poll') {
          startManualPolling()
        } else {
          const first = await poll() // 첫 화면은 REST 로 바로 채우고
          if (closed) return
          if (first === 'not-configured') setConnection({ state: 'not-configured' })
          connect() // 이후는 WebSocket push
        }
      } catch (error) {
        if (closed) return
        setStocks(useStockStore.getState().stocks, error.message)
        setConnection({ state: 'disconnected' })
        stocksTimer = setTimeout(loadStocks, POLL_MS)
      }
    }

    setConnection({ state: 'connecting' })
    loadStocks()

    return () => {
      closed = true
      clearTimeout(reconnectTimer)
      clearTimeout(heartbeatTimer)
      clearTimeout(stocksTimer)
      clearInterval(statusTimer)
      stopPolling()
      if (socket) {
        socket.onclose = null
        socket.close()
      }
    }
  }, [feedMode, setStocks, setQuote, setConnection])
}