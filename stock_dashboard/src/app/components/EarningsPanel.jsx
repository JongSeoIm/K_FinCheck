'use client'

export default function EarningsPanel({ symbol }) {
  return (
    <div className='empty-state' style={{ padding: '16px 12px' }}>
      <strong>OpenDART 실적 공시 준비 중</strong>
      <p style={{ marginTop: '6px', color: 'var(--muted)', fontSize: '11px' }}>
        금융감독원 전자공시시스템(OpenDART) 사업보고서 재무제표가 연동될 예정입니다.
      </p>
    </div>
  )
}
