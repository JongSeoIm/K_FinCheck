'use client'

import useHydrated from '@/app/hooks/useHydrated'
import useStockStore from '@/app/store/useStockStore'
import { formatClock } from '@/app/lib/format'

const FEED_LABELS = {
  idle: ['대기', 'muted'],
  connecting: ['서버 연결 중', 'warn'],
  open: ['실시간 연결됨', 'ok'],
  reconnecting: ['재연결 중', 'warn'],
  polling: ['2초 간격 조회 중', 'ok'],
  error: ['조회 실패', 'bad'],
}

const PROVIDER_NAMES = { KIS: 'KIS' }

// 서버가 외부 시세 제공처와 연결된 상태
function sourceLabel(status) {
  if (!status) return ['상태 확인 중', 'muted']
  if (status.feedStatus === 'DEMO') return ['모의 데이터 (API 키 미설정)', 'demo']
  const name = PROVIDER_NAMES[status.dataMode] || status.dataMode
  if (status.feedStatus === 'CONNECTED') return [`${name} 실시간 수신 중`, 'ok']
  if (status.feedStatus === 'CONNECTING') return [`${name} 연결 중`, 'warn']
  return [`${name} 연결 끊김`, 'bad']
}

// 전달 방식(Push/Polling) 선택과 연결 상태·데이터 출처·장 운영 여부·마지막 갱신 시각 표시
export default function FeedStatusBar({ feed, marketStatus, statusError }) {
  const hydrated = useHydrated()
  const feedMode = useStockStore((state) => state.feedMode)
  const setFeedMode = useStockStore((state) => state.setFeedMode)

  const [feedLabel, feedTone] = FEED_LABELS[feed.status] || FEED_LABELS.idle
  const [sourceText, sourceTone] = statusError ? ['서버 상태 확인 실패', 'bad'] : sourceLabel(marketStatus)

  return (
    <section className='status-bar' aria-label='시세 연결 상태'>
      <div className='mode-toggle' role='group' aria-label='시세 전달 방식'>
        {[['push', 'Push (WebSocket)'], ['poll', 'Polling (2초)']].map(([value, label]) => (
          <button
            key={value}
            type='button'
            className={hydrated && feedMode === value ? 'active' : ''}
            aria-pressed={hydrated && feedMode === value}
            onClick={() => setFeedMode(value)}
          >
            {label}
          </button>
        ))}
      </div>

      <ul className='status-items'>
        <li><span className={`dot ${feedTone}`} />{feedLabel}</li>
        <li><span className={`dot ${sourceTone}`} />{sourceText}</li>
        <li>
          <span className={`dot ${marketStatus?.marketOpen ? 'ok' : 'muted'}`} />
          {marketStatus ? (marketStatus.marketOpen ? '정규장 운영 중' : '정규장 운영 시간 외') : '장 상태 확인 중'}
        </li>
        <li className='status-time'>마지막 수신 {formatClock(feed.lastReceivedAt)}</li>
      </ul>

      {feed.error && <p className='status-error' role='status'>{feed.error}</p>}
    </section>
  )
}
