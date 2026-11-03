'use client'

import { useEffect, useState } from 'react'
import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'

const LABELS = {
  connecting: { text: '연결 중…', tone: 'pending', title: '서버에 연결하는 중입니다.' },
  live: { text: '실시간', tone: 'ok', title: 'WebSocket 으로 KIS 실시간 체결가를 받고 있습니다.' },
  'server-polling': { text: '주기 갱신', tone: 'warn', title: 'WebSocket 은 연결됐지만 KIS 실시간이 끊겨 서버가 10초마다 현재가를 보내고 있습니다.' },
  polling: { text: '주기 갱신', tone: 'warn', title: 'WebSocket 이 끊겨 10초마다 현재가를 다시 받고 있습니다. 재연결을 시도 중입니다.' },
  'manual-polling': { text: 'Polling 2초', tone: 'ok', title: 'Polling 방식을 선택해 2초마다 서버에서 현재가를 받고 있습니다.' },
  disconnected: { text: '연결 끊김', tone: 'error', title: '서버에 연결할 수 없습니다. 자동으로 다시 시도합니다.' },
  'not-configured': { text: 'KIS 키 미설정', tone: 'error', title: '백엔드 application-local.properties 에 KIS 앱키를 넣어 주세요.' },
}

const MODES = [
  ['push', 'Push (실시간)'],
  ['poll', 'Polling (2초)'],
]

// 시세 전달 방식(Push/Polling) 선택 + 연결 상태 · 장 마감 · 마지막 갱신 시각
export default function ConnectionBadge() {
  const hydrated = useHydrated()
  const connection = useStockStore((state) => state.connection)
  const feedMode = useStockStore((state) => state.feedMode)
  const setFeedMode = useStockStore((state) => state.setFeedMode)
  const [, tick] = useState(0)

  // 표시를 주기적으로 다시 그립니다
  useEffect(() => {
    const timer = setInterval(() => tick((value) => value + 1), 5000)
    return () => clearInterval(timer)
  }, [])

  const label = LABELS[connection.state] || LABELS.connecting
  const updated = connection.lastUpdated
    ? new Date(connection.lastUpdated).toLocaleTimeString('ko-KR')
    : '—'
  const mode = hydrated ? feedMode : 'push'

  return (
    <div className='connection-status' role='status' aria-live='polite'>
      <div className='feed-toggle' role='group' aria-label='시세 전달 방식'>
        {MODES.map(([value, text]) => (
          <button
            key={value}
            type='button'
            className={mode === value ? 'active' : ''}
            aria-pressed={mode === value}
            onClick={() => setFeedMode(value)}
            disabled={!hydrated}
          >
            {text}
          </button>
        ))}
      </div>
      <span className={`connection-badge ${label.tone}`} title={label.title}>
        <i aria-hidden='true' />
        {label.text}
      </span>
      {connection.marketOpen === false && <span className='market-closed'>장 마감</span>}
      <span className='last-updated'>마지막 갱신 {updated}</span>
    </div>
  )
}
