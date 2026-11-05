'use client'

import useApi from '../hooks/useApi'
import { dateTime, eokWon, signedPercent } from '../lib/format'
import { PanelEmpty, PanelError, PanelLoading } from './PanelState'

// 금액을 표시할 수 없는 이유
const UNAVAILABLE_TEXT = {
  AMBIGUOUS: '값 확인 필요',
  UNSUPPORTED_CURRENCY: '원화 외 통화',
}

function compareLabel({ comparison, changeRate }) {
  if (comparison === 'TURN_PROFIT') return { text: '흑자 전환', tone: 'positive' }
  if (comparison === 'TURN_LOSS') return { text: '적자 전환', tone: 'negative' }
  if (comparison === 'LOSS_CONTINUE') return { text: '적자 지속', tone: 'negative' }
  if (comparison === 'NORMAL') {
    return {
      text: signedPercent(changeRate, 1),
      tone: changeRate > 0 ? 'positive' : changeRate < 0 ? 'negative' : '',
    }
  }
  return { text: '비교 불가', tone: 'earnings-muted' }
}

function AmountBar({ year, value, largest, status, previous }) {
  const width = value == null || largest === 0 ? 0 : (Math.abs(value) / largest) * 100
  const fillClass = ['earnings-bar-fill', previous && 'previous', value < 0 && 'loss'].filter(Boolean).join(' ')
  return (
    <div className='earnings-bar'>
      <span>{year}</span>
      <div className='earnings-bar-track' aria-hidden='true'>
        <div className={fillClass} style={{ width: `${width}%` }} />
      </div>
      <strong>{eokWon(value, UNAVAILABLE_TEXT[status])}</strong>
    </div>
  )
}

function AccountCompare({ item, year }) {
  const label = compareLabel(item)
  const largest = Math.max(Math.abs(item.currentAmount ?? 0), Math.abs(item.previousAmount ?? 0))
  return (
    <li className='earnings-account'>
      <div className='earnings-account-title'>
        <strong>{item.accountName}</strong>
        <span className={label.tone}>{label.text}</span>
      </div>
      <AmountBar year={year} value={item.currentAmount} largest={largest} status={item.valueStatus} />
      <AmountBar year={year - 1} value={item.previousAmount} largest={largest} status={item.valueStatus} previous />
    </li>
  )
}

// 최근 실적: DB 에 저장한 OpenDART 사업보고서의 당기·전기 비교
export default function EarningsPanel({ code }) {
  const { status, data, error, reload } = useApi(`/api/kis/financials/${code}`)

  if (status === 'loading') return <PanelLoading />
  if (status === 'error') {
    if (error?.code === 'DART_NOT_CONFIGURED') {
      return <PanelEmpty title='OpenDART 키가 설정되지 않았습니다.'>백엔드 application-local.properties 에 dart.api-key 를 넣어 주세요.</PanelEmpty>
    }
    if (error?.code === 'NO_FINANCIALS' || error?.code === 'NO_CORP_CODE') {
      return <PanelEmpty title='실적 데이터가 없습니다.'>{error.message}</PanelEmpty>
    }
    return <PanelError error={error} onRetry={reload} />
  }

  const items = Array.isArray(data?.items) ? data.items : []
  if (items.length === 0) return <PanelEmpty title='실적 데이터가 없습니다.' />

  const collectedAt = dateTime(data.collectedAt)

  return (
    <>
      <p className='earnings-basis'>{data.companyName} · {data.businessYear}년 {data.reportName} · {data.basis} 기준 · 단위 억 원</p>
      {data.stale && (
        <p className='earnings-stale' role='status'>최신 자료를 받지 못해 저장된 자료를 표시합니다.</p>
      )}
      <ul className='earnings-accounts'>
        {items.map((item) => <AccountCompare key={item.accountCode} item={item} year={data.businessYear} />)}
      </ul>
      <small className='panel-source'>
        출처: {data.source}{collectedAt && ` · 수집 ${collectedAt}`} ·{' '}
        <a href={data.url} target='_blank' rel='noreferrer'>DART 공시 원문</a>
      </small>
    </>
  )
}