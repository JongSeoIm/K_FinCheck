'use client'

import { useEffect, useState } from 'react'
import { seoulDate } from '../lib/companyEvents'

const backendUrl = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'

function currentMonth() {
  const [year, month] = seoulDate().split('-').map(Number)
  return new Date(year, month - 1, 1)
}

export default function useCompanyCalendar(enabled) {
  const [month, setMonth] = useState(currentMonth)
  const [result, setResult] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const monthKey = `${month.getFullYear()}-${String(month.getMonth() + 1).padStart(2, '0')}`
  const monthResult = enabled && result?.month === monthKey && result.attempt === attempt ? result : null

  useEffect(() => {
    if (!enabled) return
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
        if (!Array.isArray(events)) throw new Error('Invalid company events response')
        if (!controller.signal.aborted) setResult({ month: monthKey, attempt, events })
      })
      .catch(() => {
        if (!controller.signal.aborted) setResult({ month: monthKey, attempt, failed: true })
      })
    return () => controller.abort()
  }, [enabled, monthKey, attempt])

  return {
    month,
    monthKey,
    monthResult,
    moveMonth: delta => setMonth(value => new Date(value.getFullYear(), value.getMonth() + delta, 1)),
    retry: () => setAttempt(value => value + 1),
  }
}
