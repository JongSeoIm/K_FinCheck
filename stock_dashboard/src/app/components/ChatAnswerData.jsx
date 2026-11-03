'use client'

const number = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 1 })
const percent = new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2, signDisplay: 'exceptZero' })
const comparisons = { TURNAROUND: '흑자 전환', TURNED_LOSS: '적자 전환', CONTINUED_LOSS: '적자 지속' }
const present = value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value))
const format = value => present(value) ? number.format(Number(value)) : '—'
const direction = value => !present(value) ? '' : Number(value) > 0 ? 'positive' : Number(value) < 0 ? 'negative' : ''
const safeLink = value => {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username }
  catch { return false }
}

export function formatChatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date) + ' (한국시간)'
}

export default function ChatAnswerData({ presentation }) {
  const data = presentation?.data
  if (!data) return null
  if (presentation.type === 'composite' && Array.isArray(data.sections)) return (
    <div className='chat-composite'>
      {data.sections.filter(section => ['quote', 'financial', 'news'].includes(section.type)).map((section, index) =>
        <ChatAnswerData key={`${section.type}-${index}`} presentation={section} />)}
      {data.notes?.length > 0 && <div className='chat-composite-notes' role='status'>
        {data.notes.map((note, index) => <span className='chat-data-label' key={index}>{note}</span>)}
      </div>}
    </div>
  )
  if (presentation.type === 'news' && Array.isArray(data.articles)) return (
    <div className='chat-news'>
      <span className='chat-data-label'>{data.name} · 검색 결과 기준</span>
      <ul className='chat-news-list' aria-label='관련 기사'>
        {data.articles.filter(article => safeLink(article.link)).map(article => <li key={article.link}>
          <a href={article.link} target='_blank' rel='noopener noreferrer'>{article.title}<span aria-hidden='true'> ↗</span></a>
          <span className='chat-data-label'>{article.publishedAt ? `게시 ${formatChatTime(article.publishedAt)}` : '게시 시각 미제공'}</span>
        </li>)}
      </ul>
    </div>
  )
  if (presentation.type === 'quote') return (
    <div className='chat-quote' aria-label={`${data.name} 현재가 조회 결과`}>
      <span className='chat-data-label'>{data.name} · {data.symbol}</span>
      <strong className='chat-quote-price'>{format(data.price)}원</strong>
      <div className={`chat-quote-change ${direction(data.changeRate)}`}>
        {present(data.priceChange) ? `${percent.format(Number(data.priceChange))}원` : '—'}
        {' · '}{present(data.changeRate) ? `${percent.format(Number(data.changeRate))}%` : '—'}
      </div>
      <span className='chat-data-label'>거래량 {format(data.volume)}주</span>
      <span className='chat-data-label'>{data.time_kind} · {formatChatTime(data.basis_at)}</span>
    </div>
  )
  if (presentation.type !== 'financial' || !Array.isArray(data.metrics)) return null
  return (
    <div className='chat-financial'>
      <div className='chat-data-label'>{data.name} · {data.businessYear}년 · {data.basis} · {data.unit}</div>
      <table className='chat-financial-table'>
        <caption className='sr-only'>{data.name} {data.businessYear}년 실적 및 전년 대비</caption>
        <thead><tr><th scope='col'>항목</th><th scope='col'>실적</th><th scope='col'>전년 대비</th></tr></thead>
        <tbody>{data.metrics.map((metric, index) => {
          const available = metric.status === 'AVAILABLE'
          const comparison = available && present(metric.current) && present(metric.previous)
            ? direction(Number(metric.current) - Number(metric.previous)) : ''
          return <tr key={`${metric.label}-${index}`}>
            <th scope='row'>{metric.label}</th>
            <td>{available ? format(metric.current) : '—'}</td>
            <td className={comparison}>{available ? comparisons[metric.comparison] ||
              (present(metric.growthRate) ? `${percent.format(Number(metric.growthRate))}%` : '—') : '—'}</td>
          </tr>
        })}</tbody>
      </table>
      {data.metrics.some(metric => metric.status !== 'AVAILABLE' || metric.growthRate == null) &&
        <span className='chat-data-label'>— 자료 없음 또는 증감률 비교 불가</span>}
    </div>
  )
}
