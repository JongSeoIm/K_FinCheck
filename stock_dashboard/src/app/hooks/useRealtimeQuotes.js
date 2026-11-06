'use client'

import { useEffect, useState } from 'react'
import useStockStore from '../store/useStockStore'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const empty = { quotes: {}, candles: {}, supportedSymbols: [], relay: 'DISABLED', kafka: 'WAITING' }

export default function useRealtimeQuotes(intervalSeconds = 1, mode = 'push') {
  const [snapshot, setSnapshot] = useState(empty)
  const [connection, setConnection] = useState('연결 준비')

  useEffect(() => {
    let disposed = false
    let socket
    let timer
    let watchdog
    let attempt = 0
    let lastMessage = Date.now()
    const controller = new AbortController()
    const url = `${BACKEND_URL}/api/realtime/snapshot?interval=${intervalSeconds}`

    function apply(data) {
      if (disposed || data.type !== 'snapshot' || data.intervalSeconds !== intervalSeconds) return
      setSnapshot(data)
      for (const [symbol, quote] of Object.entries(data.quotes || {})) {
        if (Number.isFinite(quote.price) && useStockStore.getState().prices[symbol] !== quote.price) {
          useStockStore.getState().setPrice(symbol, quote.price)
        }
      }
    }

    async function fetchSnapshot() {
      const response = await fetch(url, { signal: controller.signal, cache: 'no-store' })
      if (!response.ok) throw new Error('실시간 서버 연결 필요')
      apply(await response.json())
    }

    async function poll() {
      try {
        await fetchSnapshot()
        if (!disposed) setConnection('Polling')
      } catch (error) {
        if (!disposed && error.name !== 'AbortError') setConnection('실시간 서버 연결 필요')
      } finally {
        if (!disposed) timer = setTimeout(poll, 2000)
      }
    }

    function connect() {
      if (disposed) return
      setConnection(attempt ? 'Push 재연결 중' : 'Push 연결 중')
      const wsUrl = new URL('/ws/quotes', BACKEND_URL)
      wsUrl.protocol = wsUrl.protocol === 'https:' ? 'wss:' : 'ws:'
      wsUrl.searchParams.set('interval', String(intervalSeconds))
      const current = new WebSocket(wsUrl)
      socket = current
      current.onopen = () => {
        if (disposed) return
        attempt = 0
        lastMessage = Date.now()
        setConnection('Push')
      }
      current.onmessage = event => {
        if (disposed || socket !== current) return
        lastMessage = Date.now()
        try { apply(JSON.parse(event.data)) } catch { setConnection('시세 응답 오류') }
      }
      current.onerror = () => { if (!disposed) setConnection('실시간 서버 연결 필요') }
      current.onclose = () => {
        if (disposed || socket !== current) return
        setConnection('Push 재연결 중')
        timer = setTimeout(connect, Math.min(15000, 1000 * 2 ** Math.min(attempt++, 4)))
      }
    }

    timer = setTimeout(() => {
      setSnapshot(empty)
      if (mode === 'poll') poll()
      else {
        connect()
        watchdog = setInterval(() => {
          if (socket?.readyState === WebSocket.OPEN && Date.now() - lastMessage > 10000) socket.close()
        }, 5000)
      }
    }, 0)

    return () => {
      disposed = true
      controller.abort()
      clearTimeout(timer)
      clearInterval(watchdog)
      if (socket) { socket.onclose = null; socket.close() }
    }
  }, [intervalSeconds, mode])

  const relayLabels = {
    DISABLED: '실시간 미시작', CONFIG_REQUIRED: 'KIS 설정 필요', CONNECTING: 'KIS 연결 중',
    SUBSCRIBING: '종목 구독 중', WAITING_TRADE: '체결 대기 · 장 운영시간 확인',
    RECEIVING: '실시간 수신 중', RECONNECTING: 'KIS 재연결 중',
    SUBSCRIPTION_ERROR: 'KIS 구독 오류', DATA_ERROR: '시세 처리 오류',
    WAITING_KAFKA: '시세 전달 서버 연결 대기', SPOOL_BLOCKED: '시세 대기 저장소 확인 필요',
  }
  const status = snapshot.outbox === 'BLOCKED' ? '시세 대기 저장소 확인 필요'
    : snapshot.kafka === 'ERROR' ? (snapshot.pendingQuotes > 0 ? `시세 전송 대기 · ${snapshot.pendingQuotes}건` : 'Kafka 전송 오류')
    : `${connection} · ${relayLabels[snapshot.relay] || '수신 상태 확인 중'}`
  return { ...snapshot, status, trades: snapshot.quotes || {} }
}
