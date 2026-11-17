'use client'

import { useEffect, useState } from 'react'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const dateFormat = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

export default function NewsFeed({ symbol }) {
  const [state, setState] = useState(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch(`${BACKEND_URL}/api/stocks/${encodeURIComponent(symbol)}/news`, { signal: controller.signal })
        const data = await response.json()
        if (!response.ok) throw new Error(data.error || '뉴스를 불러오지 못했습니다.')
        if (!controller.signal.aborted) setState({ symbol, attempt, data })
      } catch (error) {
        if (!controller.signal.aborted) setState({ symbol, attempt,
          error: error.name === 'TypeError' ? '뉴스 서버에 연결하지 못했습니다.' : error.message })
      }
    }
    load()
    return () => controller.abort()
  }, [symbol, attempt])

  const current = state?.symbol === symbol && state.attempt === attempt ? state : null
  if (!current) return <div className='financial-state' role='status'>뉴스 조회 중…</div>
  if (current.error) return <div className='financial-state' role='status'>
    <p>{current.error}</p><button type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
  </div>
  const articles = current.data.articles || []
  if (!articles.length) return <div className='financial-state' role='status'>관련 기사가 없습니다.</div>

  return (
    <>
      <div className='news-toolbar'><span>최신순 · 네이버 뉴스 검색</span><span>검색 결과 설명 · 원문은 기사 링크</span></div>
      <div className='news-scroll' tabIndex={0} aria-label='관련 기사 목록'>
        <ol className='news-list'>
          {articles.map((article, index) => (
            <li key={article.link}>
              <span className='news-index' aria-hidden='true'>{String(index + 1).padStart(2, '0')}</span>
              <div className='news-copy'>
                <a href={article.link} target='_blank' rel='noopener noreferrer'>{article.title}</a>
                {article.description && <p>{article.description}</p>}
                {article.publishedAt && <small><time dateTime={article.publishedAt}>{dateFormat.format(new Date(article.publishedAt))}</time></small>}
              </div>
              <span className='news-link-label' aria-hidden='true'>원문 ↗</span>
            </li>
          ))}
        </ol>
      </div>
    </>
  )
}
