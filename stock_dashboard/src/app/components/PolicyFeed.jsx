'use client'

import { useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const dateFormat = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
})
const relationLabels = { DIRECT: '기업 직접 언급', INDIRECT: '산업 간접 관련' }
const statusLabels = {
  ANNOUNCED: '발표 자료',
  CONFIRMED: '본문에 확정 표현 포함',
  PLANNED: '본문에 시행 예정 표현 포함',
  IN_EFFECT: '본문에 시행 표현 포함',
}

export default function PolicyFeed({ symbol }) {
  const [state, setState] = useState(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch(`${BACKEND_URL}/api/stocks/${encodeURIComponent(symbol)}/policies`, { signal: controller.signal })
        const data = await response.json()
        if (!response.ok) throw new Error(data.error || '산업·정책 자료를 불러오지 못했습니다.')
        if (!controller.signal.aborted) setState({ symbol, attempt, data })
      } catch (error) {
        if (!controller.signal.aborted) setState({ symbol, attempt,
          error: error.name === 'TypeError' ? '산업·정책 자료 서버에 연결하지 못했습니다.' : error.message })
      }
    }
    load()
    return () => controller.abort()
  }, [symbol, attempt])

  const current = state?.symbol === symbol && state.attempt === attempt ? state : null
  if (!current) return <div className='financial-state' role='status'>산업·정책 자료 조회 중…</div>
  if (current.error) return <div className='financial-state' role='status'>
    <p>{current.error}</p><button type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
  </div>
  const policies = current.data.policies || []
  if (!policies.length) return <div className='financial-state' role='status'>현재 수집 범위에서 관련도가 충분한 산업·정책 자료가 없습니다.</div>

  return (
    <>
      <div className='policy-toolbar'><span>산업통상부·정책브리핑 · 관련도 기준 통과</span><span>허용된 텍스트만 수집 · 원문 출처 표시</span></div>
      <div className='policy-scroll' tabIndex={0} aria-label='산업·정책 동향 목록'>
        <ol className='policy-list'>
          {policies.map((policy, index) => (
            <li key={policy.link}>
              <span className='policy-index' aria-hidden='true'>{String(index + 1).padStart(2, '0')}</span>
              <div className='policy-copy'>
                {policy.tags?.length > 0 && <div className='policy-tags' aria-label='자료 분류'>
                  {policy.tags.map(tag => <span className='policy-tag' key={tag}>{tag}</span>)}
                </div>}
                <a href={policy.link} target='_blank' rel='noopener noreferrer'>{policy.title}</a>
                {policy.summary && <p>{policy.summary}</p>}
                <small>
                  {relationLabels[policy.relationType] || '관련성 확인 필요'} · {statusLabels[policy.policyStatus] || '발표 자료'}
                  {' · '}{policy.source === 'KOREA_KR' ? `정책브리핑${policy.publisher ? ` · ${policy.publisher}` : ''}` : '산업통상부'}
                  {policy.publishedAt && <> · <time dateTime={policy.publishedAt}>{dateFormat.format(new Date(`${policy.publishedAt}T00:00:00+09:00`))}</time></>}
                </small>
              </div>
              <span className='policy-link-label' aria-hidden='true'>원문 ↗</span>
            </li>
          ))}
        </ol>
      </div>
    </>
  )
}
