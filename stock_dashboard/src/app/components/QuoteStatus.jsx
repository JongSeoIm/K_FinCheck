'use client'

import { useEffect, useState } from 'react'

const clock = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul',
  weekday: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })

export default function QuoteStatus({ quote, fetchedAt, source }) {
  const [now, setNow] = useState(null)
  useEffect(() => {
    const initial = setTimeout(() => setNow(Date.now()), 0)
    const timer = setInterval(() => setNow(Date.now()), 15000)
    return () => { clearTimeout(initial); clearInterval(timer) }
  }, [])
  const parts = now == null ? null : Object.fromEntries(clock.formatToParts(now).map(part => [part.type, part.value]))
  const minutes = parts ? Number(parts.hour) * 60 + Number(parts.minute) : null
  // 시간대 안내일 뿐, 공휴일·임시휴장·시간외 세션의 실제 운영 판정은 아니다.
  const status = !parts ? '시간 확인 중' : ['Sat', 'Sun'].includes(parts.weekday) ? '주말'
    : minutes < 540 ? '정규장 시작 전' : minutes < 930 ? '정규장 시간대' : '정규장 시간 외'
  const timestamp = quote?.exchangeTime || fetchedAt
  const date = timestamp ? new Date(timestamp) : null
  const time = date && Number.isFinite(date.getTime())
    ? clock.format(date).replace(/^[A-Za-z]+,?\s*/, '') : null
  return <div className='quote-status'>
    <span title='한국시간 기준 시간대 안내입니다. 휴장일·시간외 거래·거래정지는 별도 확인이 필요합니다.'>KRX · {status}<small>한국시간 기준</small></span>
    <span>{source && <>{source} · </>}{time ? `${quote?.exchangeTime ? '최근 체결' : '마지막 조회'} ${time}` : '체결 시각 미수신'}</span>
  </div>
}
