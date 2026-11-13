'use client'

import { ResponsiveContainer, LineChart, Line, XAxis, YAxis, Tooltip, CartesianGrid } from 'recharts'

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
  if (presentation.type === 'financial_comparison' && Array.isArray(data.reports)) {
    const labels = ['매출액', '영업이익', '당기순이익']
    const years = data.years || data.reports.map(report => report.businessYear)
    const rows = years.map(year => {
      const report = data.reports.find(report => report.businessYear === year)
      const row = { year }
      for (const label of labels) {
        const metric = report?.metrics.find(metric => metric.label === label)
        row[label] = metric?.status === 'AVAILABLE' && present(metric.current) ? Number(metric.current) : null
      }
      return row
    })
    return <div className='chat-financial' aria-label='여러 연도 실적 비교'>
      <div className='chat-data-label'>{data.name} · {data.basis} · {data.unit}</div>
      <table className='chat-financial-table'>
        <caption className='sr-only'>{data.name} 연도별 실적 비교</caption>
        <thead><tr><th scope='col'>사업연도</th>{labels.map(label => <th scope='col' key={label}>{label}</th>)}</tr></thead>
        <tbody>{rows.map(row => <tr key={row.year}><th scope='row'>{row.year}년</th>{labels.map(label => <td key={label}>{format(row[label])}</td>)}</tr>)}</tbody>
      </table>
      {labels.map(label => <div key={label}>
        <span className='chat-data-label'>{label} 추이 · {data.unit}</span>
        <ResponsiveContainer width='100%' height={150}>
          <LineChart data={rows} margin={{ left: 5, right: 12, top: 12, bottom: 5 }}>
            <CartesianGrid vertical={false} stroke='#e4e4e4' />
            <XAxis dataKey='year' tick={{ fontSize: 12 }} />
            <YAxis width={68} tick={{ fontSize: 11 }} tickFormatter={value => new Intl.NumberFormat('ko-KR', { notation: 'compact', maximumFractionDigits: 1 }).format(value)} />
            <Tooltip formatter={value => [`${format(value)}억 원`, label]} labelFormatter={value => `${value}년`} />
            <Line type='linear' dataKey={label} stroke='#555b5f' strokeWidth={2} dot={{ r: 3 }} connectNulls={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>)}
      {data.notes?.map(note => <span key={note} className='chat-data-label'>{note}</span>)}
      <span className='chat-data-label'>— 자료 없음 · 누락된 연도의 그래프는 연결하지 않습니다.</span>
    </div>
  }
  if (presentation.type === 'composite' && Array.isArray(data.sections)) return (
    <div className='chat-composite'>
      {data.sections.filter(section => ['quote', 'financial', 'policy'].includes(section.type)).map((section, index) =>
        <ChatAnswerData key={`${section.type}-${index}`} presentation={section} />)}
      {data.notes?.length > 0 && <div className='chat-composite-notes' role='status'>
        {data.notes.map((note, index) => <span className='chat-data-label' key={index}>{note}</span>)}
      </div>}
    </div>
  )
  if (presentation.type === 'policy' && Array.isArray(data.documents)) return (
    <div className='chat-policy'>
      <span className='chat-data-label'>{data.name} · 산업통상부 보도·참고자료</span>
      <ul className='chat-policy-list' aria-label='관련 산업·정책 자료'>
        {data.documents.filter(document => safeLink(document.link)).map(document => <li key={document.link}>
          {document.tags?.length > 0 && <div className='policy-tags' aria-label='자료 분류'>
            {document.tags.map(tag => <span className='policy-tag' key={tag}>{tag}</span>)}
          </div>}
          <a href={document.link} target='_blank' rel='noopener noreferrer'>{document.title}<span aria-hidden='true'> ↗</span></a>
          <span className='chat-data-label'>
            {document.relationType === 'DIRECT' ? '기업 직접 언급' : '산업 간접 관련'}
            {' · '}{document.policyStatus === 'IN_EFFECT' ? '본문에 시행 표현 포함' : document.policyStatus === 'PLANNED' ? '본문에 시행 예정 표현 포함' : document.policyStatus === 'CONFIRMED' ? '본문에 확정 표현 포함' : '발표 자료'}
            {document.publishedAt ? ` · 발표 ${document.publishedAt}` : ''}
          </span>
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
