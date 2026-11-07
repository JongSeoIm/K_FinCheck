'use client'

import { useEffect, useState } from 'react'
import { stockApi } from '@/api/stockApi'

const dateFmt = new Intl.DateTimeFormat('ko-KR', {
  month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Seoul',
})

const NAVER_API_HUB_URL = 'https://www.ncloud.com/product/applicationService/naverApiHub'

// 선택 종목 관련 뉴스 목록 (우리 서버 /api/stocks/{symbol}/news → 네이버 검색 API)
// 네이버 검색 결과를 받은 순서 그대로 보여 준다. 분류·재정렬하지 않고 AI 에도 넘기지 않는다.
export default function NewsFeed({ symbol }) {
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
        <a className='news-provider' href={NAVER_API_HUB_URL} target='_blank' rel='noreferrer'>NAVER API HUB 제공</a>
        <span>{result.news.length}개 기사</span>
      </div>

      <div className='news-scroll'>
        {result.news.length === 0 ? (
          <p className='empty-copy'>최근 관련 뉴스가 없습니다.</p>
        ) : (
          <ul className='news-list'>
            {result.news.map((item, index) => (
              <li key={`${index}-${item.id}`}>
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
      <p className='news-disclaimer'>네이버 검색 결과이며 기사 전문을 표시하지 않습니다.</p>
    </>
  )
}
