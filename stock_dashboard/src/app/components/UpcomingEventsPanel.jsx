'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { seoulDate } from '../lib/companyEvents'
import CompanyEventList from './CompanyEventList'

const backendUrl = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

export default function UpcomingEventsPanel() {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState(null)
  const current = result?.attempt === attempt ? result : null

  useEffect(() => {
    const controller = new AbortController()

    async function load() {
      try {
        const today = seoulDate()
        const [year, month] = today.split('-').map(Number)
        const nextMonth = new Date(Date.UTC(year, month, 1)).toISOString().slice(0, 7)
        const months = [today.slice(0, 7), nextMonth]
        const groups = await Promise.all(months.map(async period => {
          const response = await fetch(`${backendUrl}/api/company-events?month=${period}`, {
            signal: controller.signal,
            cache: 'no-store',
          })
          if (!response.ok) throw new Error('Company events unavailable')
          const events = await response.json()
          if (!Array.isArray(events)) throw new Error('Invalid company events response')
          return events
        }))
        const events = groups.flat().filter(event => event.date >= today)
          .toSorted((a, b) => a.date.localeCompare(b.date)
            || (a.time || '99:99').localeCompare(b.time || '99:99')
            || a.id.localeCompare(b.id))
          .slice(0, 3)
        if (!controller.signal.aborted) setResult({ attempt, events })
      } catch {
        if (!controller.signal.aborted) setResult({ attempt, error: true })
      }
    }

    load()
    return () => controller.abort()
  }, [attempt])

  return <section className='panel upcoming-events-panel' aria-labelledby='upcoming-events-heading'>
    <div className='panel-heading'><h2 id='upcoming-events-heading'>다가오는 기업 일정</h2></div>
    {!current ? <p className='upcoming-events-state' role='status'>일정을 불러오는 중입니다.</p>
      : current.error ? <div className='upcoming-events-state' role='status'>
        <p>기업 일정을 불러오지 못했습니다.</p>
        <button className='upcoming-events-retry' type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
      </div>
      : current.events.length ? <CompanyEventList events={current.events} />
        : <p className='upcoming-events-state'>이번 달·다음 달의 예정 일정이 없습니다.</p>}
    <Link className='upcoming-events-link' href='/?view=calendar'>기업 일정 보기</Link>
  </section>
}
