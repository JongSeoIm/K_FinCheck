'use client'

import { useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { FaChevronLeft, FaChevronRight } from 'react-icons/fa'
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

function seoulDate() {
  return new Date().toLocaleDateString('sv-SE', { timeZone: 'Asia/Seoul' })
}

function currentMonth() {
  const [year, month] = seoulDate().split('-').map(Number)
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
  const [selectedDate, setSelectedDate] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const calendar = useRef(null)
  const year = month.getFullYear()
  const index = month.getMonth()
  const firstDay = month.getDay()
  const days = new Date(year, index + 1, 0).getDate()
  const cellCount = Math.ceil((firstDay + days) / 7) * 7
  const monthKey = `${year}-${String(index + 1).padStart(2, '0')}`
  const monthResult = result?.month === monthKey && result.attempt === attempt ? result : null
  const events = monthResult?.events ?? []
  const today = seoulDate()
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
        if (!controller.signal.aborted) setResult({ month: monthKey, attempt, events })
      })
      .catch(() => {
        if (!controller.signal.aborted) setResult({ month: monthKey, attempt, failed: true })
      })
    return () => controller.abort()
  }, [monthKey, attempt])

  useEffect(() => {
    if (opened === null) return
    function closeOutside(event) {
      if (!calendar.current?.contains(event.target)) setOpened(null)
    }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [opened])

  function moveMonth(delta) {
    setHovered(null)
    setOpened(null)
    setSelectedDate(null)
    setResult(null)
    setMonth(new Date(year, index + delta, 1))
  }

  function toggle(id, date) {
    setHovered(null)
    setOpened(opened === id ? null : id)
    if (date) setSelectedDate(date)
  }

  // 달력 칸이 좁은 화면에서도 일정 전체를 읽을 수 있도록 같은 자료를 날짜순 목록으로 보여 준다.
  const agenda = (selectedDate ? events.filter(event => event.date === selectedDate) : events)
    .toSorted((a, b) => a.date.localeCompare(b.date) || (a.time || '').localeCompare(b.time || ''))
  const selectedDay = selectedDate ? Number(selectedDate.slice(8)) : null

  function renderPopup(id, label, entries) {
    return active === id && (
      <div className={styles.popup} id={id} role='region' aria-label={label}>
        {entries.map(event => (
          <div className={styles.popupItem} key={event.id}>
            <EventDetail event={event} />
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
          <button type='button' onClick={() => moveMonth(-1)} aria-label='이전 달'><FaChevronLeft aria-hidden='true' /></button>
          <h2 aria-live='polite'>{year}년 {index + 1}월</h2>
          <button type='button' onClick={() => moveMonth(1)} aria-label='다음 달'><FaChevronRight aria-hidden='true' /></button>
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
            const selected = selectedDate === date || opened === moreId || entries.some(event => opened === eventId(event))
            const dayClass = [styles.day, selected && styles.selectedDay, entries.length > 0 && styles.hasEvents].filter(Boolean).join(' ')
            return (
              <div className={dayClass} key={cell}>
                <button type='button' className={styles.dateButton} aria-pressed={selectedDate === date}
                  aria-current={date === today ? 'date' : undefined}
                  aria-label={`${index + 1}월 ${day}일${date === today ? ' 오늘' : ''}, ${entries.length ? `일정 ${entries.length}건` : '일정 없음'}`}
                  onClick={() => setSelectedDate(current => current === date ? null : date)}>
                  <time dateTime={date}>{day}</time>
                  {entries.length > 0 && <span className={styles.count} aria-hidden='true'>{entries.length}</span>}
                </button>
                {entries.slice(0, 2).map(event => {
                  const id = eventId(event)
                  const label = `${event.name} · ${eventLabels[event.type]}`
                  return (
                    <div className={styles.event} key={id}
                      onPointerEnter={e => { if (e.pointerType === 'mouse' && opened === null) setHovered(id) }}
                      onPointerLeave={() => setHovered(current => current === id ? null : current)}>
                      <button type='button' className={[styles.eventButton, eventTones[event.type]].filter(Boolean).join(' ')} id={`${id}-trigger`} aria-label={label}
                        aria-expanded={active === id} aria-controls={active === id ? id : undefined}
                        onClick={() => toggle(id, date)}>
                        <span>{event.name}</span><span>{eventLabels[event.type]}</span>
                      </button>
                      {renderPopup(id, label, [event])}
                    </div>
                  )
                })}
                {extra.length > 0 && (
                  <div className={styles.event}>
                    <button type='button' id={`${moreId}-trigger`} className={styles.more} aria-expanded={active === moreId}
                      aria-controls={active === moreId ? moreId : undefined} onClick={() => toggle(moreId, date)}>+{extra.length}건</button>
                    {renderPopup(moreId, `${day}일 추가 일정`, extra)}
                  </div>
                )}
              </div>
            )
          })}
        </div>
        {events.length > 0 && (
          <section className={styles.agenda} aria-labelledby='calendar-agenda-heading'>
            <div className={styles.agendaHeading}>
              <h3 id='calendar-agenda-heading'>
                {selectedDay ? `${index + 1}월 ${selectedDay}일 일정` : `${index + 1}월 전체 일정`}
              </h3>
              <span className={styles.agendaCount}>{agenda.length}건</span>
              {selectedDate && <button type='button' onClick={() => setSelectedDate(null)}>이달 전체 보기</button>}
            </div>
            {agenda.length === 0 ? <p className={styles.agendaEmpty} role='status'>선택한 날짜에 등록된 일정이 없습니다.</p> : (
              <ol className={styles.agendaList}>
                {agenda.map(event => (
                  <li key={event.id} className={eventTones[event.type]}>
                    <time className={styles.agendaDate} dateTime={event.date}>
                      {Number(event.date.slice(5, 7))}.{Number(event.date.slice(8))}
                      <small>{weekdays[new Date(`${event.date}T00:00:00`).getDay()]}</small>
                    </time>
                    <div className={styles.agendaBody}>
                      <p className={styles.agendaTitle}>
                        <strong>{event.name}</strong>
                        <span className={styles.agendaType}>{eventLabels[event.type]}</span>
                      </p>
                      <EventDetail event={event} compact />
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </section>
        )}
        {events.length === 0 && (
          <div className={styles.empty}>
            <span role='status'>{!monthResult ? '기업 일정을 불러오는 중입니다.'
              : monthResult.failed ? '기업 일정을 불러오지 못했습니다.'
              : '이 달에 등록된 일정이 없습니다.'}</span>
            {monthResult?.failed && <button type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>}
          </div>
        )}
      </div>
    </section>
  )
}

// 팝업과 일정 목록이 같은 항목을 같은 순서로 보여 주도록 한 곳에서 그린다.
function EventDetail({ event, compact = false }) {
  return <>
    {!compact && <strong>{event.name} {eventLabels[event.type]}</strong>}
    <span className={styles.eventTitle}>{event.title}</span>
    <dl className={styles.eventFacts}>
      <div><dt>일시</dt><dd>{event.date}{event.time && ` ${event.time.slice(0, 5)} (KST)`}</dd></div>
      {event.announcedDate && event.announcedDate !== event.date && <div><dt>공지일</dt><dd>{event.announcedDate}</dd></div>}
      {event.venue && <div><dt>장소</dt><dd>{event.venue}</dd></div>}
      {event.agenda && <div><dt>주요 안건</dt><dd>{event.agenda}</dd></div>}
    </dl>
    <span className={styles.eventLinks}>
      <a href={event.sourceUrl} target='_blank' rel='noopener noreferrer'>{sources[event.source] ?? event.source} 원문 ↗</a>
      <Link href={`/?symbol=${event.symbol}`}>종목 보기</Link>
    </span>
  </>
}
