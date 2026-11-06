'use client'

import { directionClass, formatClock, formatNumber, formatPrice, formatRate, formatSignedPrice, formatVolume } from '@/app/lib/format'

const eok = new Intl.NumberFormat('ko-KR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })
const formatEok = (v) => (v == null ? '—' : eok.format(v / 100_000_000))

function BarRow({ label, value, max, strong }) {
  const width = value == null || !max ? 0 : Math.max(1, (Math.abs(value) / max) * 100)
  return (
    <div className='annual-bar chat-bar'>
      <span>{label}</span>
      <i><b className={strong ? 'current' : ''} style={{ width: `${width}%` }} /></i>
    </div>
  )
}

function QuoteCard({ data }) {
  return (
    <section className='chat-visual'>
      <header><strong>{data.name}</strong><span>{data.symbol}</span></header>
      <div className='chat-quote-main'>
        <strong>{formatPrice(data.price)}</strong>
        <span className={directionClass(data.changeAmount)}>{formatSignedPrice(data.changeAmount)} {formatRate(data.changeRate)}</span>
      </div>
      <dl className='chat-quote-grid'>
        <div><dt>시가</dt><dd>{formatNumber(data.open)}</dd></div>
        <div><dt>고가</dt><dd>{formatNumber(data.high)}</dd></div>
        <div><dt>저가</dt><dd>{formatNumber(data.low)}</dd></div>
        <div><dt>누적 거래량</dt><dd>{formatVolume(data.volume)}</dd></div>
      </dl>
      <small>{data.source} · {data.eventTime ? `체결 ${formatClock(data.eventTime)}` : `조회 ${formatClock(data.receivedAt)}`}</small>
    </section>
  )
}

function FinancialCard({ data }) {
  return (
    <section className='chat-visual'>
      <header><strong>{data.name}</strong><span>{data.year}년 {data.report} · {data.fs} · 억 원</span></header>
      <table className='chat-table'>
        <thead><tr><th scope='col'>항목</th><th scope='col'>실적</th><th scope='col'>전년 대비</th></tr></thead>
        <tbody>
          {data.rows.map((row) => (
            <tr key={row.code}>
              <th scope='row'>{row.name}</th>
              <td>{formatEok(row.current)}</td>
              <td className={row.changeRate == null ? '' : directionClass(row.changeRate)}>
                {row.changeRate == null ? row.compareLabel : formatRate(row.changeRate)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {data.rows.map((row) => {
        const max = Math.max(Math.abs(row.current ?? 0), Math.abs(row.previous ?? 0))
        return (
          <div className='chat-bars' key={row.code} aria-hidden='true'>
            <em>{row.name}</em>
            <BarRow label='전기' value={row.previous} max={max} />
            <BarRow label='당기' value={row.current} max={max} strong />
          </div>
        )
      })}
      <small>
        {data.source} · {data.period}
        {data.url && <> · <a href={data.url} target='_blank' rel='noopener noreferrer'>공시 원문 ↗</a></>}
      </small>
    </section>
  )
}

// 두 기업 이상의 실적을 같은 항목끼리 막대로 비교
function FinancialCompare({ cards }) {
  const codes = [...new Set(cards.flatMap((card) => card.rows.map((row) => row.code)))]
  return (
    <section className='chat-visual'>
      <header><strong>실적 비교</strong><span>억 원</span></header>
      {codes.map((code) => {
        const rows = cards.map((card) => ({ card, row: card.rows.find((item) => item.code === code) }))
        const max = Math.max(...rows.map(({ row }) => Math.abs(row?.current ?? 0)))
        const name = rows.find(({ row }) => row)?.row.name
        return (
          <div className='chat-bars' key={code}>
            <em>{name}</em>
            {rows.map(({ card, row }, index) => (
              <div className='chat-compare-row' key={card.symbol}>
                <BarRow label={card.name} value={row?.current} max={max} strong={index === 0} />
                <span>{formatEok(row?.current)}</span>
              </div>
            ))}
          </div>
        )
      })}
      <small>{cards.map((card) => `${card.name} ${card.period}`).join(' · ')}. 기간이 다르면 직접 비교에 주의하세요.</small>
    </section>
  )
}

function NewsLinks({ data }) {
  return (
    <section className='chat-visual'>
      <header><strong>{data.name} 관련 기사</strong><span>{data.source}</span></header>
      <ul className='chat-news'>
        {data.items.map((item) => (
          <li key={item.url}>
            <a href={item.url} target='_blank' rel='noopener noreferrer'>{item.title} ↗</a>
            <small>{item.press} · {formatClock(item.publishedAt)}</small>
          </li>
        ))}
      </ul>
    </section>
  )
}

function CompanyCard({ data }) {
  return (
    <section className='chat-visual'>
      <header><strong>{data.name}</strong><span>{data.symbol} · {data.market}</span></header>
    </section>
  )
}

// AI 답변 아래에 조회한 자료를 내용에 맞게 카드·표·그래프·링크로 보여준다 (서버가 Tool 결과로 만든 값)
export default function ChatAnswerVisuals({ visuals }) {
  if (!visuals?.length) return null
  const financial = visuals.filter((item) => item.type === 'financial')
  return (
    <div className='chat-visuals'>
      {visuals.map((item) => {
        const key = `${item.type}-${item.symbol}`
        if (item.type === 'quote') return <QuoteCard key={key} data={item} />
        if (item.type === 'financial') return <FinancialCard key={key} data={item} />
        if (item.type === 'news') return <NewsLinks key={key} data={item} />
        if (item.type === 'company') return <CompanyCard key={key} data={item} />
        return null
      })}
      {financial.length > 1 && <FinancialCompare cards={financial} />}
    </div>
  )
}
