'use client'

export default function RecommendationChart({ symbol }) {
  return (
    <div className='empty-state' style={{ padding: '16px 12px' }}>
      <strong>국내 증권사 컨센서스 준비 중</strong>
      <p style={{ marginTop: '6px', color: 'var(--muted)', fontSize: '11px' }}>
        국내 기업 리포트 및 목표 주가 데이터는 다음 단계에서 연동될 예정입니다.
      </p>
    </div>
  )
}
