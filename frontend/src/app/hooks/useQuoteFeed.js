'use client'

import { useEffect, useRef, useState } from 'react'
import { stockApi, WS_URL } from '@/api/stockApi'
import useStockStore from '@/app/store/useStockStore'

const POLL_INTERVAL_MS = 2000
const MAX_RECONNECT_DELAY_MS = 15000

/**
 * 선택한 방식으로 최신 시세를 받아 store에 반영한다.
 * - push: 우리 서버 WebSocket(/ws/quotes)에 연결해 종목을 구독. 끊기면 다시 연결하고 재구독(→ 서버가 최신 스냅샷을 보내줌)
 * - poll: 2초마다 우리 서버 REST(/api/quotes)를 조회. 이전 요청이 끝나기 전에는 다음 요청을 보내지 않음
 * 두 방식은 동시에 실행되지 않는다. (mode가 바뀌면 이전 방식은 정리)
 */
export default function useQuoteFeed(symbols, mode) {
  const setQuote = useStockStore((state) => state.setQuote)
  const [feed, setFeed] = useState({ status: 'idle', lastReceivedAt: null, error: '' })
  const symbolsKey = [...new Set(symbols)].sort().join(',')
  const symbolsRef = useRef([])
  const socketRef = useRef(null)
  const subscribedRef = useRef(new Set())

  // 구독 종목이 바뀌면 열린 WebSocket에 추가 구독·해제만 보낸다 (재연결 X)
  useEffect(() => {
    const next = symbolsKey ? symbolsKey.split(',') : []
    symbolsRef.current = next
    const socket = socketRef.current
    if (mode !== 'push' || !socket || socket.readyState !== WebSocket.OPEN) return

    const subscribed = subscribedRef.current
    const added = next.filter((s) => !subscribed.has(s))
    const removed = [...subscribed].filter((s) => !next.includes(s))
    if (added.length) socket.send(JSON.stringify({ type: 'subscribe', symbols: added }))
    if (removed.length) socket.send(JSON.stringify({ type: 'unsubscribe', symbols: removed }))
    subscribedRef.current = new Set(next)
  }, [symbolsKey, mode])

  // Push 방식
  useEffect(() => {
    if (mode !== 'push') return undefined
    let disposed = false
    let retry = 0
    let reconnectTimer = null

    const connect = () => {
      setFeed((f) => ({ ...f, status: retry === 0 ? 'connecting' : 'reconnecting' }))
      const socket = new WebSocket(WS_URL)
      socketRef.current = socket

      socket.onopen = () => {
        retry = 0
        const current = symbolsRef.current
        subscribedRef.current = new Set(current)
        if (current.length) socket.send(JSON.stringify({ type: 'subscribe', symbols: current }))
        setFeed((f) => ({ ...f, status: 'open', error: '' }))
      }

      socket.onmessage = (event) => {
        let message
        try {
          message = JSON.parse(event.data)
        } catch {
          return
        }
        if (message.type === 'quote') {
          setQuote(message.data)
          setFeed((f) => ({ ...f, lastReceivedAt: Date.now() }))
        } else if (message.type === 'error') {
          setFeed((f) => ({ ...f, error: message.message }))
        }
      }

      socket.onclose = () => {
        if (disposed) return
        const delay = Math.min(MAX_RECONNECT_DELAY_MS, 1000 * 2 ** retry)
        retry += 1
        setFeed((f) => ({ ...f, status: 'reconnecting', error: '서버 실시간 연결이 끊겼습니다. 다시 연결하는 중입니다.' }))
        reconnectTimer = setTimeout(connect, delay)
      }
    }

    connect()

    return () => {
      disposed = true
      clearTimeout(reconnectTimer)
      socketRef.current?.close()
      socketRef.current = null
      subscribedRef.current = new Set()
    }
  }, [mode, setQuote])

  // Polling 방식
  useEffect(() => {
    if (mode !== 'poll') return undefined
    let disposed = false
    let inFlight = false
    let controller = null

    const poll = async () => {
      const current = symbolsRef.current
      if (inFlight || current.length === 0) return
      inFlight = true
      controller = new AbortController()
      try {
        const list = await stockApi.getQuotes(current, { signal: controller.signal })
        if (disposed) return
        list.forEach(setQuote)
        setFeed({ status: 'polling', lastReceivedAt: Date.now(), error: '' })
      } catch (error) {
        if (!disposed && error.name !== 'AbortError') {
          setFeed((f) => ({ ...f, status: 'error', error: error.message }))
        }
      } finally {
        inFlight = false
      }
    }

    setFeed((f) => ({ ...f, status: 'polling', error: '' }))
    poll()
    const timer = setInterval(poll, POLL_INTERVAL_MS)

    return () => {
      disposed = true
      clearInterval(timer)
      controller?.abort()
    }
  }, [mode, setQuote])

  return feed
}
