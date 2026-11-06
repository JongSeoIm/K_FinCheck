'use client'

import { useEffect, useRef, useState } from 'react'
import { stockApi, WS_URL } from '@/api/stockApi'
import useStockStore from '@/app/store/useStockStore'

const POLL_INTERVAL_MS = 2000
const MAX_RECONNECT_DELAY_MS = 15000

/**
 * 선택한 방식으로 최신 시세를 받아 store에 반영한다.
 * - push: 우리 서버 WebSocket(/ws/quotes)에 연결해 종목과 차트 봉을 구독. 서버가 0.5초 간격으로 보낸다. 끊기면 다시 연결하고 재구독
 * - poll: 우리 서버 REST(/api/quotes)를 조회하고, 응답을 받은 뒤 2초 후에 다시 조회
 * 두 방식은 동시에 실행되지 않는다. (mode가 바뀌면 이전 방식은 정리)
 */
export default function useQuoteFeed(symbols, mode) {
  const setQuote = useStockStore((state) => state.setQuote)
  const setLiveCandle = useStockStore((state) => state.setLiveCandle)
  const chartSubscription = useStockStore((state) => state.chartSubscription)
  const [feed, setFeed] = useState({ status: 'idle', lastReceivedAt: null, error: '' })
  const symbolsKey = [...new Set(symbols)].sort().join(',')
  const symbolsRef = useRef([])
  const socketRef = useRef(null)
  const subscribedRef = useRef(new Set())
  const chartRef = useRef(null)

  // 차트 봉 구독 (열린 WebSocket에 바로 알린다. 연결되면 onopen 에서 다시 보낸다)
  useEffect(() => {
    chartRef.current = chartSubscription
    const socket = socketRef.current
    if (mode !== 'push' || !socket || socket.readyState !== WebSocket.OPEN) return
    socket.send(JSON.stringify({ type: 'chart', ...(chartSubscription ?? {}) }))
  }, [chartSubscription, mode])

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
        if (chartRef.current) socket.send(JSON.stringify({ type: 'chart', ...chartRef.current }))
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
        } else if (message.type === 'candle') {
          setLiveCandle(message.symbol, message.interval, message.data)
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
  }, [mode, setQuote, setLiveCandle])

  // Polling 방식
  useEffect(() => {
    if (mode !== 'poll') return undefined
    let disposed = false
    let timer = null
    let controller = null

    // 응답(성공·실패)을 받은 뒤 2초 후에 다음 요청을 보낸다
    const poll = async () => {
      const current = symbolsRef.current
      if (current.length > 0) {
        controller = new AbortController()
        try {
          const list = await stockApi.getQuotes(current, { signal: controller.signal })
          if (disposed) return
          list.forEach(setQuote)
          setFeed({ status: 'polling', lastReceivedAt: Date.now(), error: '' })
        } catch (error) {
          if (disposed || error.name === 'AbortError') return
          setFeed((f) => ({ ...f, status: 'error', error: error.message }))
        }
      }
      if (!disposed) timer = setTimeout(poll, POLL_INTERVAL_MS)
    }

    setFeed((f) => ({ ...f, status: 'polling', error: '' }))
    poll()

    return () => {
      disposed = true
      clearTimeout(timer)
      controller?.abort()
    }
  }, [mode, setQuote])

  return feed
}
