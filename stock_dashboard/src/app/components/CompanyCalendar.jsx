'use client'

import { useRef, useState } from 'react'
import Link from 'next/link'
import { eventLabels, seoulDate } from '../lib/companyEvents'
import styles from './CompanyCalendar.module.css'
import StockIcon from './StockIcon'

const weekdays = ['일', '월', '화', '수', '목', '금', '토']
const sources = { OPENDART: 'OpenDART', KIND: 'KIND', COMPANY_IR: '기업 IR' }

export default function CompanyCalendar({ data }) {
  const { month, monthKey, monthResult, retry } = data
  const [selection, setSelection] = useState(null)
  const list = useRef(null)
  const groups = useRef(new Map())
  const year = month.getFullYear()
  const index = month.getMonth()
  const firstDay = new Date(year, index, 1).getDay()
  const days = new Date(year, index + 1, 0).getDate()
  const cellCount = Math.ceil((firstDay + days) / 7) * 7
  const today = seoulDate()
  const selectedDate = selection?.startsWith(`${monthKey}-`) ? selection : null
  const grouped = new Map()
  for (const event of [...(monthResult?.events ?? [])].sort((a, b) =>
    a.date.localeCompare(b.date) || (a.time ?? '').localeCompare(b.time ?? '') || a.name.localeCompare(b.name))) {
    if (!grouped.has(event.date)) grouped.set(event.date, [])
    grouped.get(event.date).push(event)
  }

  function selectDate(date) {
    setSelection(date)
    const target = groups.current.get(date)
    if (target && list.current) {
      list.current.style.paddingBottom = Math.max(0, list.current.clientHeight - target.offsetHeight) + 'px'
      const top = target.getBoundingClientRect().top - list.current.getBoundingClientRect().top + list.current.scrollTop
      list.current.scrollTo({ top, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' })
    }
  }

  return (
    <section className={styles.calendar} aria-labelledby='calendar-heading'>
      <div className='market-title'><h1 id='calendar-heading'>기업 일정</h1></div>
      <div className={styles.layout}>
        <div className={styles.monthPanel}>
          <div className={styles.monthBar}>
            <h2 aria-live='polite'>{year}년 {index + 1}월</h2>
            <div className={styles.monthActions}>
              <button type='button' onClick={() => { setSelection(null); data.moveMonth(-1) }} aria-label='이전 달'>‹</button>
              <button type='button' onClick={() => { setSelection(null); data.moveMonth(1) }} aria-label='다음 달'>›</button>
            </div>
          </div>
          <div className={styles.grid} key={monthKey}>
            {weekdays.map(day => <div className={styles.weekday} key={day}>{day}</div>)}
            {Array.from({ length: cellCount }, (_, cell) => {
              const day = cell - firstDay + 1
              if (day < 1 || day > days) return <div key={cell} aria-hidden='true' />
              const date = `${monthKey}-${String(day).padStart(2, '0')}`
              const count = grouped.get(date)?.length ?? 0
              return <button type='button' key={date}
                className={[styles.day, count > 0 && styles.hasEvents].filter(Boolean).join(' ')}
                aria-label={`${index + 1}월 ${day}일, 일정 ${count}건`}
                aria-pressed={date === selectedDate} aria-current={date === today ? 'date' : undefined}
                onClick={() => selectDate(date)}>
                <time dateTime={date}>{day}</time>
                {count > 0 && <span className={styles.dot} aria-hidden='true' />}
              </button>
            })}
          </div>
          <p className={styles.selectionStatus} role='status'>
            {selectedDate && !grouped.has(selectedDate) && `${Number(selectedDate.slice(-2))}일에는 등록된 일정이 없습니다.`}
          </p>
        </div>
        <div className={styles.agenda}>
          <div className={styles.agendaHeading}><h2>{index + 1}월 전체 일정</h2></div>
          <div className={styles.agendaList} ref={list} key={monthKey} tabIndex={0} role='region' aria-label={`${index + 1}월 기업 일정 목록`}>
            {[...grouped].map(([date, entries]) => (
              <section className={styles.dateGroup} key={date}
                ref={node => { if (node) groups.current.set(date, node); else groups.current.delete(date) }}
                aria-labelledby={`agenda-${date}`} data-selected={date === selectedDate}>
                <h3 id={`agenda-${date}`}><time dateTime={date}>{Number(date.slice(-2))}일 <span>{weekdays[new Date(`${date}T12:00:00+09:00`).getUTCDay()]}요일</span></time></h3>
                {entries.map(event => (
                  <details className={styles.event} key={event.id}>
                    <summary>
                      <StockIcon symbol={event.symbol} />
                      <span className={styles.eventCopy}><strong>{event.name}</strong><span>{eventLabels[event.type] ?? event.type}</span></span>
                      <span className={styles.eventTitle}>{event.title?.replace(/([^\s(])\(/g, '$1 (')}</span>
                      <span className={styles.eventTime}>{event.time?.slice(0, 5)}</span>
                      <span className={styles.chevron} aria-hidden='true'>›</span>
                    </summary>
                    <div className={styles.eventDetail}>
                      {event.announcedDate && event.announcedDate !== event.date && <p>공지일 {event.announcedDate}</p>}
                      {event.venue && <p>장소 · {event.venue}</p>}
                      {event.agenda && <p>주요 안건 · {event.agenda}</p>}
                      <div className={styles.eventLinks}>
                        {event.sourceUrl && <a href={event.sourceUrl} target='_blank' rel='noopener noreferrer'>{sources[event.source] ?? event.source} 원문 ↗</a>}
                        <Link href={`/?symbol=${event.symbol}`}>종목 보기 ↗</Link>
                      </div>
                    </div>
                  </details>
                ))}
              </section>
            ))}
            {grouped.size === 0 && <div className={styles.empty} role='status'>
              <p>{!monthResult ? '기업 일정을 불러오는 중입니다.' : monthResult.failed ? '기업 일정을 불러오지 못했습니다.' : '이 달에 등록된 일정이 없습니다.'}</p>
              {monthResult?.failed && <button type='button' onClick={retry}>다시 조회</button>}
            </div>}
          </div>
        </div>
      </div>
    </section>
  )
}
