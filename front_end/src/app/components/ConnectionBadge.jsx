'use client'

import { useEffect, useState } from 'react'
import useStockStore from '@/app/store/useStockStore'

const LABELS = {
  connecting: { text: '연결 중…', tone: 'pending', title: '서버에 연결하는 중입니다.' },
  live: { text: '실시간', tone: 'ok', title: 'WebSocket 으로 KIS 실시간 체결가를 받고 있습니다.' },
  'server-polling': { text: '주기 갱신', tone: 'warn', title: 'WebSocket 은 연결됐지만 KIS 실시간이 끊겨 서버가 10초마다 현재가를 보내고 있습니다.' },
  polling: { text: '주기 갱신', tone: 'warn', title: 'WebSocket 이 끊겨 10초마다 현재가를 다시 받고 있습니다. 재연결을 시도 중입니다.' },
  disconnected: { text: '연결 끊김', tone: 'error', title: '서버에 연결할 수 없습니다. 자동으로 다시 시도합니다.' },
  'not-configured': { text: 'KIS 키 미설정', tone: 'error', title: '백엔드 application-local.properties 에 KIS 앱키를 넣어 주세요.' },
}

export default function ConnectionBadge() {
  const connection = useStockStore((state) => state.connection)
  const [, tick] = useState(0)

  // "n초 전" 표시를 위해 5초마다 다시 그립니다
  useEffect(() => {
    const timer = setInterval(() => tick((value) => value + 1), 5000)
    return () => clearInterval(timer)
  }, [])

  const label = LABELS[connection.state] || LABELS.connecting
  const updated = connection.lastUpdated
    ? new Date(connection.lastUpdated).toLocaleTimeString('ko-KR')
    : '—'

  return (
    <div className='connection-status' role='status' aria-live='polite'>
      <span className={`connection-badge ${label.tone}`} title={label.title}>
        <i aria-hidden='true' />
        {label.text}
      </span>
      {connection.marketOpen === false && <span className='market-closed'>장 마감</span>}
      <span className='last-updated'>마지막 갱신 {updated}</span>
    </div>
  )
}
