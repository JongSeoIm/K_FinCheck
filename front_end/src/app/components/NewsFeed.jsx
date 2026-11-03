'use client'

import { Fragment, useMemo, useState } from 'react'
import useApi from '../hooks/useApi'
import { PanelEmpty, PanelError, PanelLoading } from './PanelState'

const categories = [
  { label: '전체', words: [] },
  { label: '실적', words: ['실적', '매출', '영업이익', '순이익', '분기', '어닝'] },
  { label: '공시', words: ['공시', '주주', '배당', '자사주', '유상', '무상', '합병'] },
  { label: '시장·투자', words: ['주가', '시장', '투자', '목표가', '외국인', '기관', '코스피'] },
]

function formatTime(iso) {
  if (!iso) return ''
  const date = new Date(iso)
  return Number.isNaN(date.getTime())
    ? ''
    : date.toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

// 관련 기사: 네이버 뉴스 검색(기업명, 최신순 최대 10개). 제목을 누르면 원문이 새 탭으로 열립니다.
// 설명은 네이버 검색 결과의 요약 문구이며 기사 전문이나 AI 요약이 아닙니다.
export default function NewsFeed({ code }) {
  const { status, data, error, reload } = useApi(`/api/kis/news/${code}`)
  const [category, setCategory] = useState('전체')
  const news = useMemo(() => (Array.isArray(data) ? data : []), [data])

  const visibleNews = useMemo(() => {
    const selected = categories.find((item) => item.label === category)
    if (!selected || selected.words.length === 0) return news
    return news.filter((item) => selected.words.some((word) => `${item.title} ${item.description}`.includes(word)))
  }, [category, news])

  if (status === 'loading') return <PanelLoading />
  if (status === 'error') {
    if (error?.code === 'NAVER_NOT_CONFIGURED') {
      return <PanelEmpty title='네이버 API 키가 설정되지 않았습니다.'>백엔드 application-local.properties 에 naver.client-id 와 naver.client-secret 을 넣어 주세요.</PanelEmpty>
    }
    return <PanelError error={error} onRetry={reload} />
  }
  if (news.length === 0) return <PanelEmpty title='검색된 관련 기사가 없습니다.' />

  return (
    <>
      <div className='news-toolbar'>
        <div className='news-categories' aria-label='뉴스 분류'>
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
              <li key={item.id || index}>
                <div className='news-index'>{String(index + 1).padStart(2, '0')}</div>
                <div className='news-copy'>
                  <a href={item.url} target='_blank' rel='noreferrer'>{item.title}</a>
                  {item.description && <p>{item.description}</p>}
                  <small>{[item.source, formatTime(item.datetime)].filter(Boolean).join(' · ')}</small>
                </div>
                <span className='news-link-label' aria-hidden='true'>원문</span>
              </li>
            ))}
          </ul>
        )}
      </div>
      <small className='panel-source'>출처: 네이버 뉴스 검색 · 설명은 검색 결과 요약이며 기사 전문이 아닙니다</small>
    </>
  )
}