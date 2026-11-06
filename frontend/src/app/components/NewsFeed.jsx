'use client'

import { Fragment, useEffect, useMemo, useState } from 'react'
import { stockApi } from '@/api/stockApi'

// 제목·요약에 들어 있는 단어로 분류한다 (목업 NewsFeed와 같은 방식, 국내 기사용 단어)
// 전체·실적·제품·기술·시장·투자는 종목 기사(related)만, 기타는 서버가 걸러낸 기사(이름만 언급·의견 글·정치 기사)만 보여준다
const CATEGORIES = [
  { label: '전체', words: [] },
  { label: '실적', words: ['실적', '매출', '영업이익', '순이익', '이익', '적자', '흑자', '분기', '어닝', '배당'] },
  { label: '제품·기술', words: ['반도체', 'hbm', '메모리', '파운드리', 'ai', '칩', '기술', '출시', '신제품', '개발', '공정', '양산'] },
  { label: '시장·투자', words: ['주가', '증시', '코스피', '외국인', '기관', '매수', '매도', '목표가', '투자', '시가총액', '상승', '하락'] },
  { label: '기타', words: [], others: true },
]

const dateFmt = new Intl.DateTimeFormat('ko-KR', {
  month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Seoul',
})

// 선택 종목 관련 뉴스 목록 (우리 서버 /api/stocks/{symbol}/news → 네이버 검색 API)
export default function NewsFeed({ symbol }) {
  const [category, setCategory] = useState('전체')
  const [retry, setRetry] = useState(0)
  const [result, setResult] = useState({ key: null, news: [], error: '' })
  const requestKey = `${symbol}:${retry}`

  useEffect(() => {
    const controller = new AbortController()
    stockApi.getNews(symbol, { signal: controller.signal })
      .then((news) => setResult({ key: requestKey, news, error: '' }))
      .catch((error) => {
        if (error.name !== 'AbortError') setResult({ key: requestKey, news: [], error: error.message })
      })
    return () => controller.abort()
  }, [symbol, requestKey])

  const visibleNews = useMemo(() => {
    const selected = CATEGORIES.find((item) => item.label === category) || CATEGORIES[0]
    if (selected.others) return result.news.filter((item) => !item.related)
    const related = result.news.filter((item) => item.related)
    if (selected.words.length === 0) return related
    return related.filter((item) => {
      const text = `${item.headline} ${item.summary}`.toLowerCase()
      return selected.words.some((word) => text.includes(word))
    })
  }, [category, result.news])

  // 새 요청의 응답 전에는 불러오는 중 (다시 시도할 때도 이전 오류 대신 표시)
  const loading = result.key !== requestKey

  if (loading) {
    return (
      <div className='skeleton-list' aria-label='뉴스를 불러오는 중'>
        {[1, 2, 3].map((item) => <span key={item} className='skeleton-row' />)}
      </div>
    )
  }

  if (result.error) {
    return (
      <div className='news-error' role='alert'>
        <strong>뉴스를 불러오지 못했습니다.</strong>
        <p>{result.error}</p>
        <button type='button' onClick={() => setRetry((n) => n + 1)}>다시 시도</button>
      </div>
    )
  }

  return (
    <>
      <div className='news-toolbar'>
        <div className='news-categories' role='group' aria-label='뉴스 분류'>
          {CATEGORIES.map((item, index) => (
            <Fragment key={item.label}>
              {index > 0 && <span className='news-separator' aria-hidden='true'>·</span>}
              <button
                type='button'
                className={category === item.label ? 'active' : ''}
                aria-pressed={category === item.label}
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
          <p className='empty-copy'>
            {result.news.length === 0
              ? '최근 관련 뉴스가 없습니다.'
              : category === '기타' ? '기타로 분류된 기사가 없습니다.' : '이 분류에 해당하는 기사가 없습니다.'}
          </p>
        ) : (
          <ul className='news-list'>
            {category === '기타' && (
              <li className='news-note'>종목명만 스치듯 나오거나, 의견 글·정치 기사로 분류된 기사입니다.</li>
            )}
            {visibleNews.map((item, index) => (
              <li key={item.id}>
                <div className='news-index'>{String(index + 1).padStart(2, '0')}</div>
                <div className='news-copy'>
                  <a href={item.url} target='_blank' rel='noreferrer'>{item.headline}</a>
                  <p>{item.summary || '기사 요약 정보가 없습니다.'}</p>
                  <small>{item.source} · {item.datetime ? dateFmt.format(new Date(item.datetime * 1000)) : '날짜 정보 없음'}</small>
                </div>
                <span className='news-link-label' aria-hidden='true'>원문</span>
              </li>
            ))}
          </ul>
        )}
      </div>
      <p className='news-disclaimer'>검색 결과 설명을 제공하며 기사 전문을 표시하지 않습니다.</p>
    </>
  )
}
