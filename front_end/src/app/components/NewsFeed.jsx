'use client'

import { Fragment, useEffect, useMemo, useState } from 'react'

const fallbackNews = [
  { id: 'fallback-1', headline: '미국 증시, 기술주 중심으로 장중 흐름 점검', source: 'Market Brief', datetime: Date.now() / 1000, summary: '실시간 API 응답을 기다리는 동안 표시되는 예시 뉴스입니다.', url: 'https://finnhub.io/' },
  { id: 'fallback-2', headline: '주요 기업 실적과 금리 전망에 투자자 관심', source: 'Daily Market', datetime: Date.now() / 1000 - 3600, summary: '기업 뉴스 API 연결 후 선택 종목의 최신 기사로 교체됩니다.', url: 'https://finnhub.io/' },
]

const categories = [
  { label: '전체', words: [] },
  { label: '실적', words: ['earnings', 'revenue', 'profit', 'sales', 'guidance', '실적', '매출', '이익'] },
  { label: '제품·기술', words: ['product', 'technology', 'launch', 'cloud', 'chip', 'iphone', 'ai ', '제품', '기술', '출시'] },
  { label: '시장·투자', words: ['stock', 'market', 'investor', 'analyst', 'shares', 'buy', 'sell', '주가', '시장', '투자'] },
]

export default function NewsFeed({ symbol }) {
  const [news, setNews] = useState([])
  const [status, setStatus] = useState('loading')
  const [category, setCategory] = useState('전체')

  useEffect(() => {
    const controller = new AbortController()

    fetch(`/api/news?symbol=${encodeURIComponent(symbol)}`, { signal: controller.signal })
      .then(async (response) => {
        const data = await response.json()
        if (!response.ok) throw new Error(data.error)
        setNews(data)
        setStatus('ready')
      })
      .catch((error) => {
        if (error.name !== 'AbortError') {
          setNews(fallbackNews)
          setStatus('fallback')
        }
      })

    return () => controller.abort()
  }, [symbol])

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
          <p className='empty-copy'>이 분류에 해당하는 기사가 없습니다.</p>
        ) : (
          <ul className='news-list'>
            {visibleNews.map((item, index) => (
              <li key={item.id || `${item.url}-${index}`}>
                <div className='news-index'>{String(index + 1).padStart(2, '0')}</div>
                <div className='news-copy'>
                  <a href={item.url} target='_blank' rel='noreferrer'>{item.headline}</a>
                  <p>{item.summary || '기사 요약 정보가 없습니다.'}</p>
                  <small>{item.source} · {new Date(item.datetime * 1000).toLocaleDateString('ko-KR')}</small>
                </div>
                <span className='news-link-label' aria-hidden='true'>원문</span>
              </li>
            ))}
            {status === 'fallback' && <li className='inline-warning'>API 지연으로 예시 뉴스를 표시합니다.</li>}
          </ul>
        )}
      </div>
    </>
  )
}
