'use client'

import { useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const dateFormat = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', dateStyle: 'medium', timeStyle: 'short',
})

export default function StockNewsFeed({ symbol }) {
  const [state, setState] = useState(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch(`${BACKEND_URL}/api/stocks/${encodeURIComponent(symbol)}/news`, {
          signal: controller.signal,
        })
        if (!response.ok) throw new Error()
        const data = await response.json()
        if (!controller.signal.aborted) setState({ attempt, data })
      } catch {
        if (!controller.signal.aborted) setState({ attempt, error: true })
      }
    }
    load()
    return () => controller.abort()
  }, [symbol, attempt])

  if (state?.attempt !== attempt) return <div className='financial-state' role='status'>뉴스 조회 중…</div>
  if (state.error) return <div className='financial-state' role='status'>
    <p>뉴스 목록을 불러오지 못했습니다.</p>
    <button type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
  </div>

  const { source, articles } = state.data
  if (!articles.length) return <div className='financial-state' role='status'>최근 조선비즈 RSS에 제목이 일치하는 기사가 없습니다.</div>

  return <>
    <div className='policy-toolbar'><span>{source} · 제목 키워드 기준</span></div>
    <ul className='stock-news-list policy-copy'>
      {articles.map(article => <li key={article.link}>
        <div className='policy-tags'>
          <span className='policy-tag'>{article.relation === 'DIRECT' ? '직접 관련' : '산업 관련'}</span>
        </div>
        <a href={article.link} target='_blank' rel='noopener noreferrer'>{article.title} ↗</a>
        <small>{source} · <time dateTime={article.publishedAt}>{dateFormat.format(new Date(article.publishedAt))}</time></small>
      </li>)}
    </ul>
  </>
}
