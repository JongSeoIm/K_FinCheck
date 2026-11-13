'use client'

import { useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import styles from './CompanyCalendar.module.css'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']
const eventLabels = {
  EARNINGS_RELEASE: '실적 발표',
  SHARE_BUYBACK: '자사주 공시',
  DIVIDEND: '배당 결정',
  DIVIDEND_RECORD_DATE: '배당기준일',
  DIVIDEND_PAYMENT: '배당금 지급 예정',
  ANNUAL_SHAREHOLDER_MEETING: '정기 주총',
  SPECIAL_SHAREHOLDER_MEETING: '임시 주총',
}
const eventTones = {
  EARNINGS_RELEASE: styles.earnings,
  SHARE_BUYBACK: styles.buyback,
  DIVIDEND: styles.dividend,
  DIVIDEND_RECORD_DATE: styles.dividend,
  DIVIDEND_PAYMENT: styles.dividend,
  ANNUAL_SHAREHOLDER_MEETING: styles.meeting,
  SPECIAL_SHAREHOLDER_MEETING: styles.meeting,
}
const sources = { OPENDART: 'OpenDART', KIND: 'KIND', COMPANY_IR: '기업 IR' }
const backendUrl = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

function currentMonth() {
  const date = new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' })
  const [year, month] = date.split('-').map(Number)
  return new Date(year, month - 1, 1)
}

function eventId(event) {
  return `calendar-event-${event.id}`
}

export default function CompanyCalendar() {
  const [month, setMonth] = useState(currentMonth)
  const [result, setResult] = useState(null)
  const [hovered, setHovered] = useState(null)
  const [opened, setOpened] = useState(null)
  const calendar = useRef(null)
  const year = month.getFullYear()
  const index = month.getMonth()
  const firstDay = month.getDay()
  const days = new Date(year, index + 1, 0).getDate()
  const cellCount = Math.ceil((firstDay + days) / 7) * 7
  const monthKey = `${year}-${String(index + 1).padStart(2, '0')}`
  const monthResult = result?.month === monthKey ? result : null
  const events = monthResult?.events ?? []
  const active = opened ?? hovered

  useEffect(() => {
    const controller = new AbortController()
    fetch(`${backendUrl}/api/company-events?month=${monthKey}`, {
      signal: controller.signal,
      cache: 'no-store',
    })
      .then(response => {
        if (!response.ok) throw new Error('Could not load company events')
        return response.json()
      })
      .then(events => {
        if (!controller.signal.aborted) setResult({ month: monthKey, events })
      })
      .catch(() => {
        if (!controller.signal.aborted) setResult({ month: monthKey, failed: true })
      })
    return () => controller.abort()
  }, [monthKey])

  useEffect(() => {
    if (opened === null) return
    function closeOutside(event) {
      if (!calendar.current?.contains(event.target)) setOpened(null)
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [opened])

  function move(delta) {
    setHovered(null)
    setOpened(null)
    setResult(null)
    setMonth(new Date(year, index + delta, 1))
  }

  function toggle(id) {
    setHovered(null)
    setOpened(opened === id ? null : id)
  }

  function renderPopup(id, label, entries) {
    return active === id && (
      <div className={styles.popup} id={id} role='region' aria-label={label}>
        {entries.map(event => (
          <div className={styles.popupItem} key={event.id}>
            <strong>{event.name} · {eventLabels[event.type]}</strong>
            <span>{event.title}</span>
            <span>{event.date}{event.time && ` · ${event.time.slice(0, 5)} (KST)`}</span>
            {event.announcedDate && event.announcedDate !== event.date && <span>공지일 {event.announcedDate}</span>}
            {event.venue && <span>장소 · {event.venue}</span>}
            {event.agenda && <span>주요 안건 · {event.agenda}</span>}
            <a href={event.sourceUrl} target='_blank' rel='noopener noreferrer'>{sources[event.source] ?? event.source} 원문 ↗</a>
            <Link href={`/?symbol=${event.symbol}`}>종목 보기 ↗</Link>
          </div>
        ))}
      </div>
    )
  }

  return (
    <section className={styles.calendar} ref={calendar} aria-labelledby='calendar-heading'
      onKeyDown={event => {
        if (event.key === 'Escape') {
          if (active) document.getElementById(`${active}-trigger`)?.focus()
          setOpened(null)
          setHovered(null)
        }
      }}>
      <div className='market-title'>
        <h1 id='calendar-heading'>기업 일정</h1>
      </div>
      <div className={styles.panel}>
        <div className={styles.monthBar}>
          <button type='button' onClick={() => move(-1)} aria-label='이전 달'>‹</button>
          <h2 aria-live='polite'>{year}년 {index + 1}월</h2>
          <button type='button' onClick={() => move(1)} aria-label='다음 달'>›</button>
        </div>
        <div className={styles.grid}>
          {weekdays.map(day => <div className={styles.weekday} key={day}>{day}</div>)}
          {Array.from({ length: cellCount }, (_, cell) => {
            const day = cell - firstDay + 1
            if (day < 1 || day > days) return <div className={styles.emptyCell} key={cell} aria-hidden='true' />
            const date = `${monthKey}-${String(day).padStart(2, '0')}`
            const entries = events.filter(event => event.date === date)
            const extra = entries.slice(2)
            const moreId = `calendar-more-${day}`
            const selected = opened === moreId || entries.some(event => opened === eventId(event))
            return (
              <div className={selected ? `${styles.day} ${styles.selectedDay}` : styles.day} key={cell}>
                <time dateTime={date}>{day}</time>
                {entries.slice(0, 2).map(event => {
                  const id = eventId(event)
                  const label = `${event.name} · ${eventLabels[event.type]}`
                  return (
                    <div className={styles.event} key={id}
                      onPointerEnter={e => { if (e.pointerType === 'mouse' && opened === null) setHovered(id) }}
                      onPointerLeave={() => setHovered(current => current === id ? null : current)}>
                      <button type='button' className={[styles.eventButton, eventTones[event.type]].filter(Boolean).join(' ')} id={`${id}-trigger`} aria-label={label}
                        aria-expanded={active === id} aria-controls={active === id ? id : undefined}
                        onClick={() => toggle(id)}>
                        <span>{event.name}</span><span>{eventLabels[event.type]}</span>
                      </button>
                      {renderPopup(id, label, [event])}
                    </div>
                  )
                })}
                {extra.length > 0 && (
                  <div className={styles.event}>
                    <button type='button' id={`${moreId}-trigger`} className={styles.more} aria-expanded={active === moreId}
                      aria-controls={active === moreId ? moreId : undefined} onClick={() => toggle(moreId)}>+{extra.length}건</button>
                    {renderPopup(moreId, `${day}일 추가 일정`, extra)}
                  </div>
                )}
              </div>
            )
          })}
        </div>
        {events.length === 0 && (
          <p className={styles.empty} role='status'>
            {!monthResult ? '기업 일정을 불러오는 중입니다.'
              : monthResult.failed ? '기업 일정을 불러오지 못했습니다.'
              : '이 달에 등록된 일정이 없습니다.'}
          </p>
        )}
      </div>
    </section>
  )
}
