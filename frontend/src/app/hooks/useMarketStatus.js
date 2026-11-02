'use client'

import { useEffect, useState } from 'react'
import { stockApi } from '@/api/stockApi'

const STATUS_INTERVAL_MS = 15000

// 서버의 시세 수신 상태(데이터 모드·외부 연결·장 운영 여부)를 15초마다 확인
export default function useMarketStatus() {
  const [state, setState] = useState({ status: null, error: '' })

  useEffect(() => {
    let controller = null

    const load = async () => {
      controller?.abort()
      controller = new AbortController()
      try {
        const status = await stockApi.getStatus({ signal: controller.signal })
        setState({ status, error: '' })
      } catch (error) {
        if (error.name !== 'AbortError') setState((s) => ({ ...s, error: error.message }))
      }
    }

    load()
    const timer = setInterval(load, STATUS_INTERVAL_MS)
    return () => {
      clearInterval(timer)
      controller?.abort()
    }
  }, [])

  return state
}
