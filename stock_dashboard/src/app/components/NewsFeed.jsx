'use client'

import { useEffect, useState } from 'react'
import { accountApi } from '@/app/lib/accountApi'

const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL || 'http://localhost:8080'
const dateFormat = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
const formatTime = value => value ? dateFormat.format(new Date(value)) : '발행 시각 미제공'

export default function NewsFeed({ symbol }) {
  const [state, setState] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [summaries, setSummaries] = useState({})

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

  async function summarize(link) {
    setSummaries(current => ({ ...current, [link]: { loading: true } }))
    try {
      const data = await accountApi(`/api/stocks/${encodeURIComponent(symbol)}/news/summary`, {
        method: 'POST', body: JSON.stringify({ link }),
      })
      setSummaries(current => ({ ...current, [link]: { data } }))
    } catch (error) {
      const message = error.status === 401 ? '로그인하면 AI 요약을 볼 수 있습니다.'
        : error.message?.includes('계정 서버') ? '요약 서버에 연결하지 못했습니다.' : error.message
      setSummaries(current => ({ ...current, [link]: { error: message } }))
    }
  }

  const current = state?.symbol === symbol && state.attempt === attempt ? state : null
  if (!current) return <div className='financial-state' role='status'>뉴스 조회 중…</div>
  if (current.error) return <div className='financial-state' role='status'>
    <p>{current.error}</p><button type='button' onClick={() => setAttempt(value => value + 1)}>다시 조회</button>
  </div>
  const { articles = [], source = '조선비즈', basis, companyName, periodDays = 5, summaryAvailable = false, summaryNotice } = current.data
  if (!articles.length) return <div className='financial-state' role='status'>
    <p>최근 {periodDays}일 {source} RSS 기사 중 제목에 {companyName} 관련 키워드가 있는 기사가 아직 없습니다. 잠시 후 다시 확인해 주세요.</p>
  </div>

  return (
    <>
      <div className='news-toolbar'><span>{companyName} 관련 기사 {articles.length}건 · 최근 {periodDays}일 · 최신순 · {source} 공식 RSS</span>
        <span>{summaryAvailable ? '기사를 골라 AI 요약을 요청할 수 있습니다' : 'AI 요약 일시 중단'}</span></div>
      <p className='news-notice'>제목에 {companyName}의 종목명·별칭·산업 키워드가 있는 기사만 보여 줍니다. {basis || '관련도는 본문 분석이 아닌 제목 키워드 기준입니다.'}</p>
      {summaryAvailable
        ? <p className='news-notice'>&apos;AI 요약&apos; 버튼이나 AI 대화(기사 링크·&quot;제목 일부&quot;와 함께 &apos;요약해줘&apos;)로 요약을 요청할 수 있습니다.</p>
        : <p className='news-notice news-summary-error' role='status'>{summaryNotice || 'AI 요약을 제공할 수 없는 상태입니다.'}</p>}
      <div className='news-scroll' tabIndex={0} aria-label={`${source} RSS 기사 목록`}>
        <ol className='news-list'>
          {articles.map((article, index) => {
            const summary = summaries[article.link]
            return (
              <li key={article.link}>
                <span className='news-index' aria-hidden='true'>{String(index + 1).padStart(2, '0')}</span>
                <div className='news-copy'>
                  <a href={article.link} target='_blank' rel='noopener noreferrer'>{article.title}</a>
                  <div className='news-meta'>
                    {article.relevanceLabel && <span className={`news-relevance ${article.relevance}`}>{article.relevanceLabel}</span>}
                    <span className='news-source'>{article.sourceName}</span>
                    <small><time dateTime={article.publishedAt || undefined}>{formatTime(article.publishedAt)}</time></small>
                  </div>
                  {summaryAvailable && !summary?.data && (
                    <button type='button' className='news-summary-button' disabled={summary?.loading}
                      onClick={() => summarize(article.link)}>
                      {summary?.loading ? 'AI 요약 생성 중…' : 'AI 요약'}
                    </button>
                  )}
                  {summary?.error && <p className='news-summary-error' role='status'>{summary.error}</p>}
                  {summary?.data && (
                    <div className='news-summary' role='region' aria-label='AI 요약'>
                      <p>{summary.data.summary}</p>
                      <small>
                        {summary.data.attribution} · 발행 {formatTime(summary.data.publishedAt)} · <a href={article.link} target='_blank' rel='noopener noreferrer'>원문 보기</a>
                        <br />{summary.data.notice}
                        {summary.data.expiresAt && <> 요약문은 {formatTime(summary.data.expiresAt)}까지 보관 후 삭제됩니다.</>}
                      </small>
                    </div>
                  )}
                </div>
                <span className='news-link-label' aria-hidden='true'>원문 ↗</span>
              </li>
            )
          })}
        </ol>
      </div>
    </>
  )
}
