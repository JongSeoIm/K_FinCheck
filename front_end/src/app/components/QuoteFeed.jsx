'use client'

import { useEffect, useState } from 'react'
import useStockStore from '@/app/store/useStockStore'
import { marketStocks } from '@/app/lib/market'

const CODES = marketStocks.map((stock) => stock.symbol)
const POLL_AFTER_RESPONSE_MS = 2000 // Polling: 응답을 받은 뒤 2초 쉬고 다시 조회
const RECONNECT_MS = 3000
const STALE_MS = { polling: 6000, push: 3000 }

// 화면에 보이지 않는 시세 수신기. 선택한 방식(Polling/Push)으로 서버의 최신 시세를 받아 store 에 넣습니다.
export default function QuoteFeed() {
  const mode = useStockStore((state) => state.feedMode)
  const setQuotes = useStockStore((state) => state.setQuotes)
  const setFeedStatus = useStockStore((state) => state.setFeedStatus)
  const [ready, setReady] = useState(false)

  // 저장해 둔 방식을 먼저 읽습니다.
  useEffect(() => {
    try {
      const saved = window.localStorage.getItem('kfc-feed-mode')
      if (saved === 'push' || saved === 'polling') useStockStore.setState({ feedMode: saved })
    } catch {
      // 저장소를 못 읽으면 기본값(Polling) 사용
    }
    setReady(true)
  }, [])

  useEffect(() => {
    if (!ready) return undefined
    let stopped = false
    let lastReceived = 0
    let timer
    let socket
    let cleanup = () => {}
    const received = () => {
      lastReceived = Date.now()
      setFeedStatus('connected')
    }
    setFeedStatus('connecting')

    if (mode === 'polling') {
      const controller = new AbortController()
      const tick = async () => {
        try {
          const response = await fetch(`/api/quote/state?codes=${CODES.join(',')}`, {
            signal: controller.signal,
            cache: 'no-store',
          })
          if (!response.ok) throw new Error('state error')
          const body = await response.json()
          setQuotes(body.quotes)
          received()
        } catch {
          if (stopped) return
          setFeedStatus('disconnected')
        }
        if (!stopped) timer = setTimeout(tick, POLL_AFTER_RESPONSE_MS)
      }
      tick()
      cleanup = () => controller.abort()
    } else {
      const url = process.env.NEXT_PUBLIC_WS_URL || `ws://${window.location.hostname}:8080/ws/stock`
      const connect = () => {
        setFeedStatus('connecting')
        socket = new WebSocket(url)
        socket.onopen = () => socket.send(JSON.stringify({ type: 'subscribe', codes: CODES }))
        socket.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data)
            if (message.type === 'quotes') {
              setQuotes(message.quotes)
              received()
            }
          } catch {
            // 형식이 틀린 메시지는 무시
          }
        }
        socket.onerror = () => socket.close()
        socket.onclose = () => {
          if (stopped) return
          setFeedStatus('disconnected')
          timer = setTimeout(connect, RECONNECT_MS)
        }
      }
      connect()
    }

    // 일정 시간 새 소식이 없으면 '지연'으로 안내
    const watcher = setInterval(() => {
      if (lastReceived && Date.now() - lastReceived > STALE_MS[mode]) setFeedStatus('stale')
    }, 1000)

    // 방식을 바꾸거나 화면을 닫으면 기존 연결·반복 요청을 모두 정리합니다.
    return () => {
      stopped = true
      clearTimeout(timer)
      clearInterval(watcher)
      cleanup()
      if (socket) {
        socket.onclose = null
        socket.close()
      }
    }
  }, [mode, ready, setQuotes, setFeedStatus])

  return null
}
