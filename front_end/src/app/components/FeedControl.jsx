'use client'

import useStockStore from '@/app/store/useStockStore'

const STATUS_TEXT = {
  connecting: '연결 중…',
  connected: '연결됨',
  stale: '수신 지연',
  disconnected: '연결 끊김 · 재연결 중',
}

// 시세 전달 방식 선택 (Polling 2초 / Push 0.5초)과 연결 상태. 차트 제목 줄 오른쪽에 둡니다.
export default function FeedControl() {
  const mode = useStockStore((state) => state.feedMode)
  const status = useStockStore((state) => state.feedStatus)
  const setFeedMode = useStockStore((state) => state.setFeedMode)

  const button = (value, label, first) => (
    <button
      type='button'
      aria-pressed={mode === value}
      onClick={() => mode !== value && setFeedMode(value)}
      style={{
        border: '1px solid #888',
        borderLeft: first ? '1px solid #888' : 0,
        background: mode === value ? '#333' : '#fff',
        color: mode === value ? '#fff' : '#333',
        padding: '3px 10px',
        fontSize: '0.75rem',
        whiteSpace: 'nowrap',
        cursor: 'pointer',
      }}
    >
      {label}
    </button>
  )

  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: '0.75rem', color: '#666' }}>
      <span style={{ display: 'inline-flex' }} role='group' aria-label='시세 전달 방식'>
        {button('polling', 'Polling 2초', true)}
        {button('push', 'Push 0.5초', false)}
      </span>
      <span role='status' style={{ whiteSpace: 'nowrap', color: status === 'connected' ? '#257a4c' : '#b26a00' }}>
        {STATUS_TEXT[status]}
      </span>
    </div>
  )
}
