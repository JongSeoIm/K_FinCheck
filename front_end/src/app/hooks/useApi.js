'use client'

import { useCallback, useEffect, useState } from 'react'

// GET 한 번 받아 오기. status: loading | ready | error, error: { code, message }
export default function useApi(url) {
  const [reloadKey, setReloadKey] = useState(0)
  const requestKey = `${url}#${reloadKey}`
  const [result, setResult] = useState({ key: null, data: null, error: null })

  useEffect(() => {
    const controller = new AbortController()

    fetch(url, { signal: controller.signal, cache: 'no-store' })
      .then(async (response) => {
        const body = await response.json().catch(() => null)
        if (!response.ok || body == null) {
          // Next 가 백엔드(8080)에 닿지 못하면 JSON 이 아닌 500 을 돌려줍니다
          const offline = body == null || response.status === 500
          const error = new Error(offline ? '서버에 연결할 수 없습니다.' : body.error || `요청 실패 (${response.status})`)
          error.code = offline ? 'OFFLINE' : body.code
          throw error
        }
        setResult({ key: requestKey, data: body, error: null })
      })
      .catch((error) => {
        if (error.name === 'AbortError') return
        setResult({
          key: requestKey,
          data: null,
          error: {
            code: error.code || 'OFFLINE',
            message: error instanceof TypeError ? '서버에 연결할 수 없습니다.' : error.message,
          },
        })
      })

    return () => controller.abort()
  }, [url, requestKey])

  const reload = useCallback(() => setReloadKey((key) => key + 1), [])

  // 아직 이번 요청의 결과가 아니면 불러오는 중
  if (result.key !== requestKey) return { status: 'loading', data: null, error: null, reload }
  return { status: result.error ? 'error' : 'ready', data: result.data, error: result.error, reload }
}
