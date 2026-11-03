'use client'

import { Fragment, useEffect, useMemo, useState } from 'react'

const categories = [
  { label: '전체', words: [] },
  { label: '실적', words: ['earnings', 'revenue', 'profit', 'sales', 'guidance', '실적', '매출', '이익'] },
  { label: '제품·기술', words: ['product', 'technology', 'launch', 'cloud', 'chip', 'iphone', 'ai ', '제품', '기술', '출시'] },
  { label: '시장·투자', words: ['stock', 'market', 'investor', 'analyst', 'shares', 'buy', 'sell', '주가', '시장', '투자'] },
]

export default function NewsFeed({ symbol }) {
  const [result, setResult] = useState({ symbol: null, news: [], error: '' })
  const [category, setCategory] = useState('전체')

  useEffect(() => {
    const controller = new AbortController()

    fetch(`/api/kis/news/${encodeURIComponent(symbol)}`, { signal: controller.signal })
      .then(async (response) => {
        const data = await response.json().catch(() => null)
        if (!response.ok || !Array.isArray(data)) {
          throw new Error(data?.error || '뉴스를 불러오지 못했습니다.')
        }
        setResult({ symbol, news: data, error: '' })
      })
      .catch((error) => {
        if (error.name !== 'AbortError') {
          setResult({ symbol, news: [], error: error.message })
        }
      })

    return () => controller.abort()
  }, [symbol])

  // 결과가 아직 이 종목의 것이 아니면 불러오는 중
  const status = result.symbol !== symbol ? 'loading' : result.error ? 'error' : 'ready'
  const news = useMemo(() => (result.symbol === symbol ? result.news : []), [result, symbol])
  const errorMessage = result.error

  const visibleNews = useMemo(() => {
    const selected = categories.find((item) => item.label === category)
    if (!selected || selected.words.length === 0) return news
    return news.filter((item) => {
      const text = `${item.headline || ''} ${item.summary || ''}`.toLowerCase()
      return selected.words.some((word) => text.includes(word))
    })
  }, [category, news])

  if (status === 'loading') {
    return <div className='skeleton-list'>{[1, 2, 3].map((item) => <span key={item} className='skeleton-row' />)}</div>
  }

  if (status === 'error') {
    return <p className='empty-copy'>{errorMessage || '뉴스를 불러오지 못했습니다.'}</p>
  }

  return (
    <>
      <div className='news-toolbar'>
        <div className='news-categories' aria-label='뉴스 카테고리'>
          {categories.map((item, index) => (
            <Fragment key={item.label}>
              {index > 0 && <span className='news-separator' aria-hidden='true'>·</span>}
              <button
                type='button'
                className={category === item.label ? 'active' : ''}
                onClick={() => setCategory(item.label)}
              >
                {item.label}
              </button>
            </Fragment>
          ))}
        </div>
        <span>{visibleNews.length}개 기사</span>
      </div>

      <div className='news-scroll'>
        {visibleNews.length === 0 ? (
          <p className='empty-copy'>{news.length === 0 ? '표시할 기사가 없습니다.' : '이 분류에 해당하는 기사가 없습니다.'}</p>
        ) : (
          <ul className='news-list'>
            {visibleNews.map((item, index) => (
              <li key={item.id || `${item.url}-${index}`}>
                <div className='news-index'>{String(index + 1).padStart(2, '0')}</div>
                <div className='news-copy'>
                  <a href={item.url} target='_blank' rel='noreferrer'>{item.headline}</a>
                  <p>{item.summary || '기사 요약 정보가 없습니다.'}</p>
                  <small>{[item.source, item.datetime ? new Date(item.datetime * 1000).toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }) : null].filter(Boolean).join(' · ')}</small>
                </div>
                <span className='news-link-label' aria-hidden='true'>원문</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  )
}
